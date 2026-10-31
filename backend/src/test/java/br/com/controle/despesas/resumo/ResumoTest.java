package br.com.controle.despesas.resumo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.controle.despesas.Movimentacao;
import br.com.controle.despesas.MovimentacaoRepository;
import br.com.controle.despesas.openfinance.ContaConectada;
import br.com.controle.despesas.openfinance.ContaConectadaRepository;
import br.com.controle.despesas.openfinance.Investimento;
import br.com.controle.despesas.openfinance.InvestimentoRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Hoje fixo em 20/10/2026. */
@SpringBootTest(properties = {
        "APP_API_KEY=integration-test-only",
        "spring.datasource.url=jdbc:h2:mem:clareza-resumo;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "gemini.api.key=",
        "pluggy.client.id=",
        "pluggy.client.secret="
})
@AutoConfigureMockMvc
@Transactional
class ResumoTest {
    private static final String KEY = "integration-test-only";

    @TestConfiguration
    static class RelogioFixo {
        @Bean
        @Primary
        Clock clockFixo() {
            return Clock.fixed(Instant.parse("2026-10-20T15:00:00Z"), ZoneId.of("America/Sao_Paulo"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired MovimentacaoRepository movimentacoes;
    @Autowired ContaConectadaRepository contas;
    @Autowired InvestimentoRepository investimentos;

    @BeforeEach
    void dados() {
        movimentacoes.deleteAll();
        contas.deleteAll();
        investimentos.deleteAll();
        conta("CORRENTE", "1000.00", null, null);
        conta("CORRENTE", "-200.00", null, null);
        conta("CARTAO_CREDITO", null, "300.00", "1000.00");
        Investimento inv = new Investimento();
        inv.setItemId("item"); inv.setInstituicao("Banco"); inv.setNome("CDB"); inv.setTipo("RENDA_FIXA");
        inv.setValorInvestido(new BigDecimal("5000.00"));
        investimentos.save(inv);

        lanc("NETFLIX.COM", "55.90", "SAIDA", "2026-08-12", false);       // detectado: 2 meses, valor estavel
        lanc("Netflix com", "55.90", "SAIDA", "2026-09-12", false);
        lanc("Salario", "3000.00", "ENTRADA", "2026-08-05", false);       // ja recebido em outubro
        lanc("Salario", "3000.00", "ENTRADA", "2026-09-05", false);
        lanc("Salario", "3000.00", "ENTRADA", "2026-10-05", false);
        lanc("Aluguel", "1500.00", "SAIDA", "2026-09-10", true);          // marcado como recorrente
        lanc("LOJA X 03/10", "100.00", "SAIDA", "2026-09-15", false);     // proxima parcela 04/10
        lanc("LOJA Y 10/10", "80.00", "SAIDA", "2026-09-16", false);      // ultima parcela
        for (String dia : new String[] {"2026-08-01", "2026-08-03", "2026-08-05", "2026-09-01"}) {
            lanc("iFood", "30.00", "SAIDA", dia, false);                   // frequente demais
        }
        lanc("Conta de luz", "100.00", "SAIDA", "2026-08-20", false);     // valor varia demais
        lanc("Conta de luz", "300.00", "SAIDA", "2026-09-20", false);
        lanc("Mercado", "200.00", "SAIDA", "2026-10-03", false);
    }

    @Test void patrimonioPrevistosEProjecaoDoMesAtual() throws Exception {
        mvc.perform(get("/api/resumo").param("periodo", "2026-10").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mesAtual").value(true))
                .andExpect(jsonPath("$.patrimonio.saldoContas").value(800.0))
                .andExpect(jsonPath("$.patrimonio.faturasAbertas").value(300.0))
                .andExpect(jsonPath("$.patrimonio.limiteDisponivel").value(700.0))
                .andExpect(jsonPath("$.patrimonio.investimentos").value(5000.0))
                .andExpect(jsonPath("$.patrimonio.patrimonioLiquido").value(5500.0))
                .andExpect(jsonPath("$.previstos.length()").value(3))
                .andExpect(jsonPath("$.previstos[0].descricao").value("Aluguel"))
                .andExpect(jsonPath("$.previstos[0].origem").value("RECORRENTE"))
                .andExpect(jsonPath("$.previstos[0].dataPrevista").value("2026-10-10"))
                .andExpect(jsonPath("$.previstos[1].descricao").value("Netflix com"))
                .andExpect(jsonPath("$.previstos[1].origem").value("DETECTADO"))
                .andExpect(jsonPath("$.previstos[2].descricao").value("LOJA X 04/10"))
                .andExpect(jsonPath("$.previstos[2].origem").value("PARCELA"))
                .andExpect(jsonPath("$.totalPrevistoSaidas").value(1655.9))
                .andExpect(jsonPath("$.totalPrevistoEntradas").value(0))
                .andExpect(jsonPath("$.saldoPrevistoFimMes").value(-1155.9))
                .andExpect(jsonPath("$.ritmo.despesasAteHoje").value(200.0))
                .andExpect(jsonPath("$.ritmo.mediaDiaria").value(10.0))
                .andExpect(jsonPath("$.ritmo.projecaoDespesasMes").value(310.0))
                .andExpect(jsonPath("$.ritmo.diasRestantes").value(11));
    }

    @Test void previstoSomeQuandoJaFoiLancadoNoMes() throws Exception {
        lanc("NETFLIX.COM 10/2026", "55.90", "SAIDA", "2026-10-12", false);
        lanc("LOJA X 04/10", "100.00", "SAIDA", "2026-10-15", false);
        mvc.perform(get("/api/resumo").param("periodo", "2026-10").header("X-API-Key", KEY))
                .andExpect(jsonPath("$.previstos.length()").value(1))
                .andExpect(jsonPath("$.previstos[0].descricao").value("Aluguel"));
    }

    @Test void mesPassadoNaoTemPrevistosNemProjecao() throws Exception {
        mvc.perform(get("/api/resumo").param("periodo", "2026-09").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mesAtual").value(false))
                .andExpect(jsonPath("$.previstos.length()").value(0))
                .andExpect(jsonPath("$.saldoPrevistoFimMes").doesNotExist())
                .andExpect(jsonPath("$.ritmo").doesNotExist())
                .andExpect(jsonPath("$.patrimonio.saldoContas").value(800.0));
    }

    @Test void periodoInvalidoRetorna400() throws Exception {
        mvc.perform(get("/api/resumo").param("periodo", "2026-13").header("X-API-Key", KEY))
                .andExpect(status().isBadRequest());
    }

    @Test void proximaParcela() {
        assertEquals("COMPRA 2/3", ResumoService.proximaParcela("COMPRA 1/3"));
        assertEquals("PARC 10/12 LOJA", ResumoService.proximaParcela("PARC 09/12 LOJA"));
        assertNull(ResumoService.proximaParcela("COMPRA 3/3"));
        assertNull(ResumoService.proximaParcela("Mercado"));
    }

    private void conta(String tipo, String saldo, String fatura, String limite) {
        ContaConectada c = new ContaConectada();
        c.setItemId("item"); c.setInstituicao("Banco"); c.setNome(tipo); c.setTipo(tipo);
        c.setSaldo(saldo == null ? null : new BigDecimal(saldo));
        c.setFaturaAtual(fatura == null ? null : new BigDecimal(fatura));
        c.setLimiteCredito(limite == null ? null : new BigDecimal(limite));
        contas.save(c);
    }

    private void lanc(String descricao, String valor, String tipo, String data, boolean recorrente) {
        Movimentacao m = new Movimentacao();
        m.setDescricao(descricao); m.setValor(new BigDecimal(valor)); m.setTipo(tipo);
        m.setData(LocalDate.parse(data)); m.setCategoria("Outros"); m.setConta("Banco"); m.setRecorrente(recorrente);
        movimentacoes.save(m);
    }
}
