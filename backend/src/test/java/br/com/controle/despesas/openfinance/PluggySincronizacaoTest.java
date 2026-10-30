package br.com.controle.despesas.openfinance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.controle.despesas.Movimentacao;
import br.com.controle.despesas.MovimentacaoRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Sincronizacao contra um stub local da Pluggy (HttpServer do JDK); nenhuma rede externa e usada. */
@SpringBootTest(properties = {
        "APP_API_KEY=integration-test-only",
        "spring.datasource.url=jdbc:h2:mem:clareza-sync;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "gemini.api.key=",
        "pluggy.client.id=cliente-de-teste",
        "pluggy.client.secret=segredo-de-teste"
})
@AutoConfigureMockMvc
class PluggySincronizacaoTest {
    private static final String KEY = "integration-test-only";
    private static final HttpServer STUB = iniciarStub();
    private static final Map<String, String> RESPOSTAS = new ConcurrentHashMap<>();
    private static volatile int statusContas = 200;

    private static HttpServer iniciarStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/auth", ex -> responder(ex, 200, "{\"apiKey\":\"chave-stub\"}"));
            server.createContext("/accounts", ex -> responder(ex, statusContas, RESPOSTAS.getOrDefault("accounts", "{\"results\":[]}")));
            server.createContext("/investments", ex -> responder(ex, 200, RESPOSTAS.getOrDefault("investments", "{\"results\":[]}")));
            server.createContext("/transactions", ex -> {
                String query = ex.getRequestURI().getQuery();
                String conta = query.substring(query.indexOf("accountId=") + 10).split("&")[0];
                responder(ex, 200, RESPOSTAS.getOrDefault("transactions:" + conta, "{\"results\":[]}"));
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void responder(com.sun.net.httpserver.HttpExchange ex, int status, String corpo) throws IOException {
        byte[] bytes = corpo.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) { os.write(bytes); }
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("pluggy.api.url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    @AfterAll
    static void encerrar() { STUB.stop(0); }

    @Autowired PluggyService service;
    @Autowired MockMvc mvc;
    @Autowired ContaConectadaRepository contas;
    @Autowired InvestimentoRepository investimentos;
    @Autowired MovimentacaoRepository movimentacoes;

    private static final String CONTA_BANCO = "{\"id\":\"acc-1\",\"name\":\"Conta Corrente\",\"type\":\"BANK\",\"balance\":100.555,\"connector\":{\"name\":\"Pluggy Bank\"}}";
    private static final String CONTA_CARTAO = "{\"id\":\"acc-2\",\"name\":\"Cartao\",\"type\":\"CREDIT\",\"balance\":250.1,\"creditData\":{\"creditLimit\":5000},\"connector\":{\"name\":\"Pluggy Bank\"}}";

    private static String tx(String id, String descricao, double valor, String data) {
        return "{\"id\":\"" + id + "\",\"description\":\"" + descricao + "\",\"amount\":" + valor + ",\"date\":\"" + data + "T00:00:00.000Z\"}";
    }

    private static void definir(String chave, String... itens) {
        RESPOSTAS.put(chave, "{\"results\":[" + String.join(",", itens) + "]}");
    }

    @BeforeEach
    void limpar() {
        movimentacoes.deleteAll();
        contas.deleteAll();
        investimentos.deleteAll();
        RESPOSTAS.clear();
        statusContas = 200;
        definir("accounts", CONTA_BANCO, CONTA_CARTAO);
        definir("investments", "{\"id\":\"inv-1\",\"name\":\"CDB\",\"institution\":\"Pluggy Bank\",\"type\":\"FIXED_INCOME\",\"balance\":1000.005,\"annualRate\":11.255}");
        definir("transactions:acc-1",
                tx("t1", "Mercado Central", -10.50, "2026-10-10"),
                tx("t2", "Salario", 1000.00, "2026-10-05"));
        definir("transactions:acc-2");
    }

    private Map<String, Object> sincronizar(String itemId) {
        return service.sincronizarItem(itemId);
    }

    @Test void sincronizarDuasVezesNaoDuplicaNada() {
        Map<String, Object> primeira = sincronizar("item-real");
        assertEquals("PLUGGY_REAL", primeira.get("origem"));
        assertEquals(2, primeira.get("contasSincronizadas"));
        assertEquals(1, primeira.get("investimentosSincronizados"));
        assertEquals(2, primeira.get("transacoesImportadas"));
        assertEquals(2, contas.count());
        assertEquals(1, investimentos.count());
        assertEquals(2, movimentacoes.count());

        Map<String, Object> segunda = sincronizar("item-real");
        assertEquals(2, segunda.get("contasSincronizadas"));
        assertEquals(1, segunda.get("investimentosSincronizados"));
        assertEquals(0, segunda.get("transacoesImportadas"));
        assertEquals(2, contas.count());
        assertEquals(1, investimentos.count());
        assertEquals(2, movimentacoes.count());
    }

    @Test void valoresMonetariosSaoArredondadosEContaPreservaIdNoUpsert() {
        sincronizar("item-real");
        ContaConectada banco = contas.findByItemIdAndIdExterno("item-real", "acc-1").orElseThrow();
        assertEquals(new BigDecimal("100.56"), banco.getSaldo());
        ContaConectada cartao = contas.findByItemIdAndIdExterno("item-real", "acc-2").orElseThrow();
        assertEquals(new BigDecimal("5000.00"), cartao.getLimiteCredito());
        assertEquals(new BigDecimal("250.10"), cartao.getFaturaAtual());
        assertEquals(new BigDecimal("1000.01"), investimentos.findByItemId("item-real").get(0).getValorInvestido());

        var idAntes = banco.getId();
        sincronizar("item-real");
        assertEquals(idAntes, contas.findByItemIdAndIdExterno("item-real", "acc-1").orElseThrow().getId());
    }

    @Test void contasEInvestimentosAusentesSaoRemovidos() {
        sincronizar("item-real");
        definir("accounts", CONTA_BANCO);
        definir("investments");
        sincronizar("item-real");
        assertEquals(1, contas.count());
        assertEquals(0, investimentos.count());
    }

    @Test void transacoesIdenticasComIdsDiferentesImportamAmbas() {
        definir("transactions:acc-1",
                tx("t1", "Cafe", -8.00, "2026-10-10"),
                tx("t2", "Cafe", -8.00, "2026-10-10"));
        assertEquals(2, sincronizar("item-real").get("transacoesImportadas"));
        assertEquals(2, movimentacoes.count());
        assertEquals(0, sincronizar("item-real").get("transacoesImportadas"));
        assertEquals(2, movimentacoes.count());
    }

    @Test void lancamentoEditadoPeloUsuarioNaoEhReimportado() {
        sincronizar("item-real");
        Movimentacao mercado = movimentacoes.findAll().stream()
                .filter(m -> m.getDescricao().equals("Mercado Central")).findFirst().orElseThrow();
        mercado.setDescricao("Mercado (editado)");
        mercado.setValor(new BigDecimal("99.99"));
        movimentacoes.saveAndFlush(mercado);

        assertEquals(0, sincronizar("item-real").get("transacoesImportadas"));
        assertEquals(2, movimentacoes.count());
        assertEquals("Mercado (editado)", movimentacoes.findById(mercado.getId()).orElseThrow().getDescricao());
        assertEquals("pluggy:t1", movimentacoes.findById(mercado.getId()).orElseThrow().getIdExterno());
    }

    @Test void linhaAntigaSemIdRecebeOIdEmVezDeDuplicar() {
        Movimentacao antiga = new Movimentacao();
        antiga.setDescricao("mercado central");
        antiga.setValor(new BigDecimal("10.50"));
        antiga.setData(LocalDate.parse("2026-10-10"));
        antiga.setCategoria("Alimentação");
        antiga.setTipo("SAIDA");
        antiga.setConta("Pluggy Bank");
        movimentacoes.saveAndFlush(antiga);

        assertEquals(1, sincronizar("item-real").get("transacoesImportadas"));
        assertEquals(2, movimentacoes.count());
        assertEquals("pluggy:t1", movimentacoes.findById(antiga.getId()).orElseThrow().getIdExterno());
    }

    @Test void descricaoLongaEhTruncadaEmCemCaracteres() {
        definir("transactions:acc-1", tx("t1", "D".repeat(180), -1.00, "2026-10-10"));
        assertEquals(1, sincronizar("item-real").get("transacoesImportadas"));
        assertEquals(100, movimentacoes.findAll().get(0).getDescricao().length());
        assertEquals(0, sincronizar("item-real").get("transacoesImportadas"));
    }

    @Test void modoSimuladoEhIdempotenteComIdsDeterministicos() {
        Map<String, Object> primeira = sincronizar("mock-sandbox-item");
        assertEquals("SANDBOX_SIMULADO", primeira.get("origem"));
        assertEquals(3, primeira.get("contasSincronizadas"));
        assertEquals(3, primeira.get("investimentosSincronizados"));
        assertEquals(5, primeira.get("transacoesImportadas"));

        Map<String, Object> segunda = sincronizar("mock-sandbox-item");
        assertEquals(3, segunda.get("contasSincronizadas"));
        assertEquals(0, segunda.get("transacoesImportadas"));
        assertEquals(3, contas.count());
        assertEquals(3, investimentos.count());
        assertEquals(5, movimentacoes.count());
        assertTrue(movimentacoes.existsByIdExterno("sandbox:mock-sandbox-item:1"));
        assertTrue(movimentacoes.existsByIdExterno("sandbox:mock-sandbox-item:5"));
    }

    @Test void falhaDaPluggyViraBadGatewaySemCriarDadosFicticios() throws Exception {
        statusContas = 500;
        mvc.perform(post("/api/open-finance/sincronizar").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":\"item-real\"}"))
                .andExpect(status().isBadGateway());
        assertEquals(0, contas.count());
        assertEquals(0, investimentos.count());
        assertEquals(0, movimentacoes.count());
    }

    @Test void itemIdAusenteOuVazioRetornaBadRequest() throws Exception {
        for (String corpo : List.of("{}", "{\"itemId\":\"\"}", "{\"itemId\":\"   \"}")) {
            mvc.perform(post("/api/open-finance/sincronizar").header("X-API-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/open-finance/sincronizar").header("X-API-Key", KEY))
                .andExpect(status().isBadRequest());
        assertEquals(0, contas.count());
    }

    @Test void sandboxSincronizadoViaApiPreservaFluxoExplicito() throws Exception {
        mvc.perform(post("/api/open-finance/sincronizar").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":\"mock-sandbox-item\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origem").value("SANDBOX_SIMULADO"));
    }

    @Test void removerItemSandboxApagaSeusLancamentosESoEles() throws Exception {
        sincronizar("mock-sandbox-item");
        Movimentacao manual = new Movimentacao();
        manual.setDescricao("Lancamento manual");
        manual.setValor(new BigDecimal("3.00"));
        manual.setData(LocalDate.parse("2026-10-10"));
        manual.setCategoria("Outros");
        manual.setTipo("SAIDA");
        movimentacoes.saveAndFlush(manual);
        sincronizar("item-real");
        assertEquals(1 + 5 + 2, movimentacoes.count());

        mvc.perform(delete("/api/open-finance/itens/mock-sandbox-item").header("X-API-Key", KEY))
                .andExpect(status().isNoContent());

        assertEquals(3, movimentacoes.count());
        assertTrue(movimentacoes.existsByIdExterno("pluggy:t1"));
        assertEquals(2, contas.count());
        assertEquals(1, investimentos.count());
        assertTrue(contas.findByItemId("mock-sandbox-item").isEmpty());
    }
}
