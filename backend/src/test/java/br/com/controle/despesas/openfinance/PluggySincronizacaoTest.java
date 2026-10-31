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
            // Rota v1 descontinuada: a Pluggy responde 410 para chaves novas.
            server.createContext("/transactions", ex -> responder(ex, 410,
                    "{\"code\":\"ENDPOINT_DEPRECATED\",\"message\":\"Use GET /v2/transactions\"}"));
            server.createContext("/v2/transactions", ex -> {
                String query = ex.getRequestURI().getQuery();
                String conta = query.substring(query.indexOf("accountId=") + 10).split("&")[0];
                if (!query.contains("dateFrom=")) {
                    responder(ex, 400, "{\"message\":\"dateFrom ausente\"}");
                    return;
                }
                String pagina = query.contains("after=cursor-2") ? ":p2" : "";
                responder(ex, 200, RESPOSTAS.getOrDefault("transactions:" + conta + pagina, "{\"results\":[],\"next\":null}"));
            });
            server.createContext("/items/", ex -> {
                String corpo = RESPOSTAS.get("item");
                responder(ex, corpo == null ? 404 : 200, corpo == null ? "{}" : corpo);
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
    @Autowired ItemPluggyRepository itens;

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
        itens.deleteAll();
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

    private static String txCompleta(String id, String descricao, double valor, String tipo, String data, String categoria) {
        return "{\"id\":\"" + id + "\",\"description\":\"" + descricao + "\",\"amount\":" + valor + ",\"type\":\"" + tipo
                + "\",\"date\":\"" + data + "\"" + (categoria == null ? "" : ",\"category\":\"" + categoria + "\"") + "}";
    }

    private Movimentacao porDescricao(String descricao) {
        return movimentacoes.findAll().stream().filter(m -> m.getDescricao().equals(descricao)).findFirst().orElseThrow();
    }

    @Test void compraNoCartaoComValorPositivoEhDespesaEPagamentoDeFaturaEhIgnorado() {
        definir("transactions:acc-2",
                txCompleta("c1", "Uber Trip", 32.90, "DEBIT", "2026-10-11T00:00:00.000Z", null),
                txCompleta("c2", "Estorno loja", -15.00, "CREDIT", "2026-10-12T00:00:00.000Z", null),
                txCompleta("c3", "Pagamento recebido", -800.00, "CREDIT", "2026-10-13T00:00:00.000Z", null),
                tx("c4", "Padaria sem type", 12.00, "2026-10-14"));
        definir("transactions:acc-1",
                txCompleta("b1", "Pagamento de fatura", -800.00, "DEBIT", "2026-10-13T00:00:00.000Z", null),
                txCompleta("b2", "Pix para minha conta", -50.00, "DEBIT", "2026-10-13T00:00:00.000Z", "Same person transfer"),
                txCompleta("b3", "Conta de luz", -120.00, "DEBIT", "2026-10-13T00:00:00.000Z", null));

        Map<String, Object> r = sincronizar("item-real");
        assertEquals(4, r.get("transacoesImportadas"));
        assertEquals(3, r.get("transacoesIgnoradas"));
        assertEquals("SAIDA", porDescricao("Uber Trip").getTipo());
        assertEquals("Transporte", porDescricao("Uber Trip").getCategoria());
        assertEquals("ENTRADA", porDescricao("Estorno loja").getTipo());
        assertEquals("SAIDA", porDescricao("Padaria sem type").getTipo());
        assertEquals("SAIDA", porDescricao("Conta de luz").getTipo());
        assertTrue(movimentacoes.findAll().stream().noneMatch(m -> m.getDescricao().startsWith("Pagamento")));
    }

    @Test void percorreTodasAsPaginasDeTransacoes() {
        // API v2: cursor opaco em "next" (query string pronta); null na ultima pagina.
        RESPOSTAS.put("transactions:acc-1", "{\"results\":[" + tx("p1", "Mercado A", -5.00, "2026-10-10")
                + "],\"next\":\"?accountId=acc-1&dateFrom=2026-08-01&after=cursor-2\"}");
        RESPOSTAS.put("transactions:acc-1:p2", "{\"results\":[" + tx("p2", "Mercado B", -6.00, "2026-10-10") + "],\"next\":null}");
        assertEquals(2, sincronizar("item-real").get("transacoesImportadas"));
        assertTrue(movimentacoes.existsByIdExterno("pluggy:p2"));
    }

    @Test void nomeDoBancoVemDoApelidoOuDoItemEContaDoCartaoIdentificada() {
        RESPOSTAS.put("item", "{\"id\":\"item-real\",\"status\":\"UPDATED\",\"connector\":{\"name\":\"MeuPluggy\"},\"consentExpiresAt\":\"2027-10-30T12:00:00.000Z\"}");
        RESPOSTAS.put("accounts", "{\"results\":[" + CONTA_BANCO + ","
                + "{\"id\":\"acc-2\",\"name\":\"FREE MASTERCARD\",\"number\":\"4177\",\"type\":\"CREDIT\",\"balance\":932.12,\"creditData\":{\"creditLimit\":5612}}]}");
        definir("transactions:acc-2", txCompleta("c1", "Netflix", 55.90, "DEBIT", "2026-10-11T00:00:00.000Z", null));
        ItemPluggy vinculado = new ItemPluggy("item-real", java.time.LocalDateTime.now());
        vinculado.setNome("Santander");
        itens.save(vinculado);

        sincronizar("item-real");
        ItemPluggy item = itens.findById("item-real").orElseThrow();
        assertEquals("Santander", item.getRotulo());
        assertEquals("UPDATED", item.getStatusPluggy());
        assertEquals(2027, item.getConsentimentoExpiraEm().getYear());
        assertTrue(item.getUltimaSincronizacao() != null);
        assertEquals("Santander · cartão 4177", porDescricao("Netflix").getConta());
        assertEquals("Santander", contas.findByItemIdAndIdExterno("item-real", "acc-2").orElseThrow().getInstituicao());
    }

    @Test void datasComHorarioUsamFusoDeBrasiliaEDatasSemHorarioSaoMantidas() {
        java.time.ZoneId sp = java.time.ZoneId.of("America/Sao_Paulo");
        assertEquals(LocalDate.parse("2026-10-10"), PluggyService.dataTransacao("2026-10-10T00:00:00.000Z", sp));
        assertEquals(LocalDate.parse("2026-10-10"), PluggyService.dataTransacao("2026-10-11T01:30:00.000Z", sp));
        assertEquals(LocalDate.parse("2026-10-11"), PluggyService.dataTransacao("2026-10-11T15:00:00.000Z", sp));
        assertEquals(null, PluggyService.dataTransacao("", sp));
    }

    @Test void vincularItemSincronizaEListaComUltimaSincronizacao() throws Exception {
        mvc.perform(post("/api/open-finance/itens").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":\"item-real\",\"nome\":\"Nubank\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instituicao").value("Nubank"))
                .andExpect(jsonPath("$.transacoesImportadas").value(2));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/open-finance/itens").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].itemId").value("item-real"))
                .andExpect(jsonPath("$[0].rotulo").value("Nubank"))
                .andExpect(jsonPath("$[0].ultimaSincronizacao").exists());

        for (String corpo : List.of("{\"itemId\":\"curto\"}", "{\"itemId\":\"mock-sandbox-item\"}", "{\"itemId\":\"id com espaco\"}")) {
            mvc.perform(post("/api/open-finance/itens").header("X-API-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.erro").exists());
        }
    }

    @Test void sincronizarTodosPulaRecentesERegistraErro() throws Exception {
        sincronizar("item-real");
        itens.save(new ItemPluggy("item-com-erro", java.time.LocalDateTime.now()));

        mvc.perform(post("/api/open-finance/sincronizar-todos").param("apenasDesatualizados", "true").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sincronizados").value(1))
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].itemId").value("item-com-erro"));

        statusContas = 500;
        mvc.perform(post("/api/open-finance/sincronizar-todos").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.falhas").value(2))
                .andExpect(jsonPath("$.itens[0].erro").exists());
        assertTrue(itens.findById("item-real").orElseThrow().getUltimoErro().contains("HTTP 500"));
        assertEquals(2, movimentacoes.count());
    }

    @Test void falhaRetornaMensagemLegivel() throws Exception {
        statusContas = 500;
        mvc.perform(post("/api/open-finance/sincronizar").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":\"item-real\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.erro").value(org.hamcrest.Matchers.containsString("HTTP 500")));
    }

    @Test void sincronizacoesSimultaneasDoMesmoItemNaoFalhamNemDuplicam() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var tarefas = java.util.List.<java.util.concurrent.Callable<Map<String, Object>>>of(
                    () -> service.sincronizarItem("item-real"),
                    () -> service.sincronizarItem("item-real"),
                    () -> service.sincronizarItem("item-real"));
            int importadas = 0;
            for (var f : pool.invokeAll(tarefas)) importadas += (int) f.get().get("transacoesImportadas");
            assertEquals(2, importadas);
            assertEquals(2, movimentacoes.count());
            assertEquals(2, contas.count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void mensagemDeFalhaNaGravacaoMostraACausaSemODetalhe() {
        var causa = new java.sql.SQLException("ERROR: duplicate key value violates unique constraint \"uk_movimentacao_id_externo\"\n  Detail: Key (id_externo)=(pluggy:x) already exists.");
        String msg = PluggyService.mensagemErro(new org.springframework.dao.DataIntegrityViolationException("could not execute statement", causa));
        assertTrue(msg.startsWith("Erro ao gravar os dados sincronizados: ERROR: duplicate key"), msg);
        assertTrue(!msg.contains("Detail"), msg);
    }

    @Test void caracteresDeControleSaoRemovidosDaDescricao() {
        definir("transactions:acc-1", tx("z1", "Mercado\\u0000 Bom", -7.00, "2026-10-10"));
        sincronizar("item-real");
        assertEquals("SAIDA", porDescricao("Mercado  Bom").getTipo());
    }

    @Test void limpaDadosDeTesteSemTocarNosBancosVinculadosNemEmLancamentosReais() throws Exception {
        service.vincularItem("item-real", "Nubank");               // banco real: 2 contas, 1 investimento, 2 lancamentos
        sincronizar("mock-sandbox-item");                           // simulacao: 3 contas, 3 investimentos, 5 lancamentos
        legado("iFood Restaurante delivery", "74.90", "Nubank");     // antigo modo simulado, sem id
        legado("Compra teste", "10.00", "Banco Conectado");          // antiga sincronizacao com o banco de teste
        legado("iFood Restaurante delivery", "52.00", "Nubank");     // lancamento real parecido: fica
        ContaConectada orfa = new ContaConectada();                  // conta de item nao vinculado (Pluggy Connect de teste)
        orfa.setItemId("item-antigo-teste"); orfa.setInstituicao("Pluggy Bank"); orfa.setNome("Conta"); orfa.setTipo("CORRENTE");
        contas.save(orfa);

        mvc.perform(post("/api/open-finance/limpar-dados-teste").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removido").value(false))
                .andExpect(jsonPath("$.contas").value(4))
                .andExpect(jsonPath("$.investimentos").value(3))
                .andExpect(jsonPath("$.lancamentos").value(7));
        assertEquals(6, contas.count());

        mvc.perform(post("/api/open-finance/limpar-dados-teste").param("confirmar", "true").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removido").value(true));
        assertEquals(2, contas.count());
        assertTrue(contas.findAll().stream().allMatch(c -> "item-real".equals(c.getItemId())));
        assertEquals(1, investimentos.count());
        assertEquals(3, movimentacoes.count());
        assertTrue(movimentacoes.existsByIdExterno("pluggy:t1"));
        assertEquals(new java.math.BigDecimal("52.00"), porDescricao("iFood Restaurante delivery").getValor());
    }

    private void legado(String descricao, String valor, String conta) {
        Movimentacao m = new Movimentacao();
        m.setDescricao(descricao); m.setValor(new java.math.BigDecimal(valor)); m.setData(java.time.LocalDate.of(2026, 10, 1));
        m.setCategoria("Outros"); m.setTipo("SAIDA"); m.setConta(conta);
        movimentacoes.save(m);
    }

    @Test void erroDaPluggyIncluiCodigoDoCorpo() throws Exception {
        statusContas = 410;
        RESPOSTAS.put("accounts", "{\"code\":\"ENDPOINT_DEPRECATED\",\"message\":\"Gone\"}");
        mvc.perform(post("/api/open-finance/sincronizar").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"itemId\":\"item-real\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.erro").value(org.hamcrest.Matchers.containsString("HTTP 410 em /accounts (ENDPOINT_DEPRECATED)")));
    }
}
