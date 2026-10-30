package br.com.controle.despesas.importacao;

import br.com.controle.despesas.Movimentacao;
import br.com.controle.despesas.MovimentacaoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "APP_API_KEY=integration-test-only",
        "spring.datasource.url=jdbc:h2:mem:clareza-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "gemini.api.key=",
        "pluggy.client.id=",
        "pluggy.client.secret="
})
@AutoConfigureMockMvc
@Transactional
class ImportacaoIntegrationTest {
    private static final String KEY = "integration-test-only";
    private static final byte[] OFX = ExtratoParserTest.OFX_CONTA_SGML.getBytes(Charset.forName("windows-1252"));

    @Autowired MockMvc mvc;
    @Autowired MovimentacaoRepository repository;

    private ResultActions enviar(byte[] conteudo, String nome, String... params) throws Exception {
        var req = multipart("/api/importacoes/extrato")
                .file(new MockMultipartFile("arquivo", nome, "application/octet-stream", conteudo))
                .header("X-API-Key", KEY);
        for (int i = 0; i < params.length; i += 2) req.param(params[i], params[i + 1]);
        return mvc.perform(req);
    }

    private long importadosDoArquivo() {
        return repository.findAll().stream().filter(m -> m.getIdExterno() != null && m.getIdExterno().startsWith("arq:")).count();
    }

    @Test void previaNaoGravaECategoriza() throws Exception {
        enviar(OFX, "extrato.ofx").andExpect(status().isOk())
                .andExpect(jsonPath("$.formato").value("OFX"))
                .andExpect(jsonPath("$.conta").value("Banco Exemplo"))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.novos").value(2))
                .andExpect(jsonPath("$.pagamentosFatura").value(1))
                .andExpect(jsonPath("$.importados").value(0))
                .andExpect(jsonPath("$.dataMaisRecente").value("2026-10-06"))
                .andExpect(jsonPath("$.itens[?(@.descricao == 'Padaria São João')].categoria").value(contains("Alimentação")))
                .andExpect(jsonPath("$.itens[?(@.descricao == 'SALARIO EMPRESA')].categoria").value(contains("Salário")))
                .andExpect(jsonPath("$.itens[?(@.descricao == 'Pagamento de fatura')].status").value(contains("PAGAMENTO_FATURA")));
        assertThat(importadosDoArquivo()).isZero();
    }

    @Test void confirmarGravaEReimportarNaoDuplica() throws Exception {
        enviar(OFX, "extrato.ofx", "confirmar", "true", "conta", "Conta Exemplo")
                .andExpect(status().isOk()).andExpect(jsonPath("$.importados").value(2));
        var salvos = repository.findAll().stream().filter(m -> m.getIdExterno() != null && m.getIdExterno().startsWith("arq:")).toList();
        assertThat(salvos).hasSize(2);
        assertThat(salvos).allSatisfy(m -> assertThat(m.getConta()).isEqualTo("Conta Exemplo"));

        enviar(OFX, "extrato.ofx", "confirmar", "true", "conta", "Conta Exemplo")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importados").value(0))
                .andExpect(jsonPath("$.jaImportados").value(2));
        assertThat(importadosDoArquivo()).isEqualTo(2);
    }

    @Test void lancamentoManualIgualViraPossivelDuplicado() throws Exception {
        Movimentacao manual = new Movimentacao();
        manual.setDescricao("Pão na padaria");
        manual.setValor(new BigDecimal("89.90"));
        manual.setData(LocalDate.of(2026, 10, 5));
        manual.setCategoria("Alimentação");
        manual.setTipo("SAIDA");
        repository.saveAndFlush(manual);

        enviar(OFX, "extrato.ofx", "confirmar", "true")
                .andExpect(jsonPath("$.possiveisDuplicados").value(1))
                .andExpect(jsonPath("$.importados").value(1));
        enviar(OFX, "extrato.ofx", "confirmar", "true", "incluirPossiveisDuplicados", "true")
                .andExpect(jsonPath("$.importados").value(1));
        assertThat(importadosDoArquivo()).isEqualTo(2);
    }

    @Test void pagamentoDeFaturaSoEntraQuandoPedido() throws Exception {
        enviar(OFX, "extrato.ofx", "confirmar", "true", "incluirPagamentosFatura", "true")
                .andExpect(jsonPath("$.importados").value(3));
    }

    @Test void usaCategoriaDoHistoricoDoUsuario() throws Exception {
        Movimentacao antiga = new Movimentacao();
        antiga.setDescricao("padaria sao joao");
        antiga.setValor(new BigDecimal("10.00"));
        antiga.setData(LocalDate.now().minusDays(20));
        antiga.setCategoria("Lazer");
        antiga.setTipo("SAIDA");
        repository.saveAndFlush(antiga);

        enviar(OFX, "extrato.ofx")
                .andExpect(jsonPath("$.itens[?(@.descricao == 'Padaria São João')].categoria").value(contains("Lazer")));
    }

    @Test void comprasIdenticasNoMesmoDiaSemIdSaoMantidas() throws Exception {
        byte[] csv = "date,title,amount\n2026-10-02,Uber Trip,20.00\n2026-10-02,Uber Trip,20.00\n"
                .getBytes(StandardCharsets.UTF_8);
        enviar(csv, "fatura.csv", "confirmar", "true", "conta", "Cartão")
                .andExpect(jsonPath("$.cartao").value(true))
                .andExpect(jsonPath("$.importados").value(2))
                .andExpect(jsonPath("$.itens[0].categoria").value("Transporte"));
        enviar(csv, "fatura.csv", "confirmar", "true", "conta", "Cartão")
                .andExpect(jsonPath("$.importados").value(0))
                .andExpect(jsonPath("$.jaImportados").value(2));
    }

    @Test void arquivoInvalidoRetornaMensagemEExigeChave() throws Exception {
        enviar("nome,idade\nAna,30\n".getBytes(StandardCharsets.UTF_8), "a.csv")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.erro", containsString("CSV não reconhecido")));
        enviar(new byte[0], "a.csv").andExpect(status().isUnprocessableEntity());
        enviar("x".repeat(100).getBytes(StandardCharsets.UTF_8), "a.csv", "conta", "c".repeat(61))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(multipart("/api/importacoes/extrato")
                        .file(new MockMultipartFile("arquivo", "extrato.ofx", "application/octet-stream", OFX)))
                .andExpect(status().isUnauthorized());
    }
}
