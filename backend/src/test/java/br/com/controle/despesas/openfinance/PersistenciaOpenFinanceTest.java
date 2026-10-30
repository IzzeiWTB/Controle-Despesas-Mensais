package br.com.controle.despesas.openfinance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.controle.despesas.Movimentacao;
import br.com.controle.despesas.MovimentacaoRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "APP_API_KEY=integration-test-only",
        "spring.datasource.url=jdbc:h2:mem:clareza-persistencia;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "gemini.api.key=",
        "pluggy.client.id=",
        "pluggy.client.secret="
})
@Transactional
class PersistenciaOpenFinanceTest {
    @Autowired ContaConectadaRepository contas;
    @Autowired InvestimentoRepository investimentos;
    @Autowired MovimentacaoRepository movimentacoes;

    private ContaConectada conta(String itemId, String idExterno) {
        ContaConectada c = new ContaConectada();
        c.setItemId(itemId);
        c.setIdExterno(idExterno);
        c.setInstituicao("Banco");
        c.setNome("Conta");
        c.setTipo("CORRENTE");
        c.setSaldo(new BigDecimal("10.00"));
        return c;
    }

    private Investimento investimento(String itemId, String idExterno) {
        Investimento i = new Investimento();
        i.setItemId(itemId);
        i.setIdExterno(idExterno);
        i.setInstituicao("Corretora");
        i.setNome("CDB");
        i.setTipo("RENDA_FIXA");
        i.setValorInvestido(new BigDecimal("100.00"));
        return i;
    }

    private Movimentacao movimentacao(String idExterno) {
        Movimentacao m = new Movimentacao();
        m.setDescricao("Teste");
        m.setValor(new BigDecimal("1.00"));
        m.setData(LocalDate.parse("2026-10-10"));
        m.setCategoria("Outros");
        m.setTipo("SAIDA");
        m.setIdExterno(idExterno);
        return m;
    }

    @Test void contaMantemAtualizadoEmInformadoNaCriacaoMasRenovaNaAtualizacao() {
        LocalDateTime antigo = LocalDateTime.of(2020, 1, 1, 0, 0);
        ContaConectada c = conta("item", "a1");
        c.setAtualizadoEm(antigo);
        c = contas.saveAndFlush(c);
        assertEquals(antigo, c.getAtualizadoEm());

        c.setNome("Conta renomeada");
        c = contas.saveAndFlush(c);
        assertTrue(c.getAtualizadoEm().isAfter(antigo), "atualizadoEm deve ser renovado no update");
    }

    @Test void contaSemAtualizadoEmRecebeDataNaCriacao() {
        assertNotNull(contas.saveAndFlush(conta("item", "a1")).getAtualizadoEm());
    }

    @Test void investimentoRenovaAtualizadoEmNaAtualizacao() {
        LocalDateTime antigo = LocalDateTime.of(2020, 1, 1, 0, 0);
        Investimento i = investimento("item", "i1");
        i.setAtualizadoEm(antigo);
        i = investimentos.saveAndFlush(i);
        assertEquals(antigo, i.getAtualizadoEm());

        i.setValorInvestido(new BigDecimal("200.00"));
        i = investimentos.saveAndFlush(i);
        assertTrue(i.getAtualizadoEm().isAfter(antigo));
    }

    @Test void moedaArredondaParaDuasCasasComHalfUp() {
        assertEquals(new BigDecimal("2.68"), PluggyService.moeda(2.675));
        assertEquals(new BigDecimal("100.56"), PluggyService.moeda(100.555));
        assertEquals(new BigDecimal("0.30"), PluggyService.moeda(0.1 + 0.2));
        assertEquals(new BigDecimal("5.00"), PluggyService.moeda(5));
        assertEquals(2, PluggyService.moeda(1234.5678).scale());
    }

    @Test void descricaoImportadaEhTruncadaEmCemCaracteres() {
        assertEquals(100, PluggyService.truncarDescricao("x".repeat(250)).length());
        assertEquals("curta", PluggyService.truncarDescricao("curta"));
    }

    @Test void idExternoDeMovimentacaoEhUnico() {
        movimentacoes.saveAndFlush(movimentacao("pluggy:1"));
        assertThrows(DataIntegrityViolationException.class, () -> movimentacoes.saveAndFlush(movimentacao("pluggy:1")));
    }

    @Test void movimentacoesSemIdExternoNaoColidem() {
        movimentacoes.saveAndFlush(movimentacao(null));
        movimentacoes.saveAndFlush(movimentacao(null));
        assertEquals(2, movimentacoes.count());
    }

    @Test void idExternoDeContaEUnicoPorItem() {
        contas.saveAndFlush(conta("item-1", "a1"));
        contas.saveAndFlush(conta("item-2", "a1"));
        assertThrows(DataIntegrityViolationException.class, () -> contas.saveAndFlush(conta("item-1", "a1")));
    }

    @Test void idExternoDeInvestimentoEUnicoPorItem() {
        investimentos.saveAndFlush(investimento("item-1", "i1"));
        investimentos.saveAndFlush(investimento("item-2", "i1"));
        assertThrows(DataIntegrityViolationException.class, () -> investimentos.saveAndFlush(investimento("item-1", "i1")));
    }

    @Test void consultasPorIdExterno() {
        movimentacoes.saveAndFlush(movimentacao("pluggy:9"));
        contas.saveAndFlush(conta("item-1", "a9"));
        assertTrue(movimentacoes.existsByIdExterno("pluggy:9"));
        assertTrue(contas.findByItemIdAndIdExterno("item-1", "a9").isPresent());
        assertTrue(contas.findByItemIdAndIdExterno("item-2", "a9").isEmpty());
    }
}
