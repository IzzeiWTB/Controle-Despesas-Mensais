package br.com.controle.despesas.openfinance;

import br.com.controle.despesas.Movimentacao;
import br.com.controle.despesas.MovimentacaoRepository;
import br.com.controle.despesas.ia.GeminiService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class PluggyService {
    private static final Logger log = LoggerFactory.getLogger(PluggyService.class);
    static final int TAMANHO_MAXIMO_DESCRICAO = 100;

    @Value("${pluggy.client.id:${PLUGGY_CLIENT_ID:}}")
    private String clientId;

    @Value("${pluggy.client.secret:${PLUGGY_CLIENT_SECRET:}}")
    private String clientSecret;

    @Value("${pluggy.api.url:https://api.pluggy.ai}")
    private String apiUrl;

    private final ContaConectadaRepository contaRepository;
    private final InvestimentoRepository investimentoRepository;
    private final MovimentacaoRepository movimentacaoRepository;
    private final GeminiService geminiService;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient httpClient;

    public PluggyService(ContaConectadaRepository contaRepository,
                         InvestimentoRepository investimentoRepository,
                         MovimentacaoRepository movimentacaoRepository,
                         GeminiService geminiService,
                         ObjectMapper json,
                         Clock clock) {
        this.contaRepository = contaRepository;
        this.investimentoRepository = investimentoRepository;
        this.movimentacaoRepository = movimentacaoRepository;
        this.geminiService = geminiService;
        this.json = json;
        this.clock = clock;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isConfigurado() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    /** Converte um double vindo do JSON para valor monetario com duas casas. */
    static BigDecimal moeda(double valor) {
        return BigDecimal.valueOf(valor).setScale(2, RoundingMode.HALF_UP);
    }

    static String truncarDescricao(String descricao) {
        if (descricao == null) return "";
        return descricao.length() > TAMANHO_MAXIMO_DESCRICAO ? descricao.substring(0, TAMANHO_MAXIMO_DESCRICAO) : descricao;
    }

    static boolean ehItemSimulado(String itemId) {
        return itemId.startsWith("mock") || itemId.startsWith("sandbox");
    }

    private String base() {
        return apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
    }

    public Map<String, Object> gerarConnectToken() {
        if (!isConfigurado()) {
            return Map.of(
                    "configurado", false,
                    "modoSimulado", true,
                    "mensagem", "Credenciais do Pluggy não configuradas. Você pode usar a sincronização simulada (sandbox) para testar contas, cartões e investimentos."
            );
        }

        try {
            String apiKey = autenticarPluggy();
            String token = solicitarConnectToken(apiKey);
            return Map.of(
                    "configurado", true,
                    "modoSimulado", false,
                    "connectToken", token
            );
        } catch (Exception e) {
            log.error("Erro ao gerar connect token do Pluggy: {}", e.getMessage());
            return Map.of(
                    "configurado", true,
                    "modoSimulado", true,
                    "erro", String.valueOf(e.getMessage()),
                    "mensagem", "Falha ao conectar com a API do Pluggy. Use a sincronização simulada."
            );
        }
    }

    private String autenticarPluggy() throws Exception {
        Map<String, String> body = Map.of("clientId", clientId, "clientSecret", clientSecret);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/auth"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new PluggyIndisponivelException("Falha na autenticação Pluggy: HTTP " + response.statusCode());
        }
        JsonNode root = json.readTree(response.body());
        return root.path("apiKey").asText();
    }

    private String solicitarConnectToken(String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + "/connect_token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-API-KEY", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new PluggyIndisponivelException("Falha ao criar Connect Token Pluggy: HTTP " + response.statusCode());
        }
        JsonNode root = json.readTree(response.body());
        return root.path("accessToken").asText();
    }

    /**
     * Sincroniza um item. Itens "mock*"/"sandbox*" usam dados simulados explicitamente;
     * qualquer outro item exige a Pluggy real e, se ela falhar, o erro e propagado (sem dados ficticios).
     */
    @Transactional
    public Map<String, Object> sincronizarItem(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId obrigatorio");
        }
        String idFinal = itemId.trim();

        if (ehItemSimulado(idFinal)) {
            return sincronizarMockSandbox(idFinal);
        }
        if (!isConfigurado()) {
            throw new PluggyIndisponivelException("Credenciais da Pluggy não configuradas; só o item de simulação (sandbox) pode ser sincronizado.");
        }
        try {
            return sincronizarComPluggyReal(idFinal);
        } catch (PluggyIndisponivelException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluggyIndisponivelException("Sincronização com a Pluggy interrompida.", e);
        } catch (Exception e) {
            log.warn("Erro ao sincronizar via API do Pluggy: {}", e.getMessage());
            throw new PluggyIndisponivelException("Falha ao sincronizar com a Pluggy: " + e.getMessage(), e);
        }
    }

    private JsonNode consultar(String apiKey, String caminho) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + caminho))
                .timeout(Duration.ofSeconds(12))
                .header("X-API-KEY", apiKey)
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new PluggyIndisponivelException("A Pluggy respondeu HTTP " + response.statusCode() + " em " + caminho.split("\\?")[0]);
        }
        return json.readTree(response.body()).path("results");
    }

    private Map<String, Object> sincronizarComPluggyReal(String itemId) throws Exception {
        String apiKey = autenticarPluggy();
        String itemParam = URLEncoder.encode(itemId, StandardCharsets.UTF_8);

        // 1. Contas (upsert por idExterno)
        JsonNode contas = consultar(apiKey, "/accounts?itemId=" + itemParam);
        Existentes<ContaConectada> existentesContas = indexarContas(itemId);
        Set<String> contasVistas = new HashSet<>();
        int contasCount = 0;
        int transacoesCount = 0;
        List<String[]> contasParaTransacoes = new ArrayList<>();
        if (contas.isArray()) {
            for (JsonNode accNode : contas) {
                String idExterno = accNode.path("id").asText("");
                if (idExterno.isBlank()) continue;
                String type = accNode.path("type").asText("BANK");
                boolean credito = "CREDIT".equalsIgnoreCase(type);
                ContaConectada c = existentesContas.porId.getOrDefault(idExterno, new ContaConectada());
                c.setItemId(itemId);
                c.setIdExterno(idExterno);
                c.setInstituicao(accNode.path("connector").path("name").asText("Banco Conectado"));
                c.setNome(accNode.path("name").asText("Conta Bancária"));
                c.setTipo(credito ? "CARTAO_CREDITO" : "CORRENTE");
                double balance = accNode.path("balance").asDouble(0.0);
                if (credito) {
                    c.setSaldo(null);
                    c.setLimiteCredito(moeda(accNode.path("creditData").path("creditLimit").asDouble(0.0)));
                    c.setFaturaAtual(moeda(balance));
                } else {
                    c.setSaldo(moeda(balance));
                    c.setLimiteCredito(null);
                    c.setFaturaAtual(null);
                }
                c.setAtualizadoEm(LocalDateTime.now(clock));
                contaRepository.save(c);
                contasVistas.add(idExterno);
                contasCount++;
                contasParaTransacoes.add(new String[]{idExterno, c.getInstituicao()});
            }
        }
        removerContasAusentes(existentesContas, contasVistas);

        for (String[] conta : contasParaTransacoes) {
            transacoesCount += importarTransacoesConta(apiKey, conta[0], conta[1]);
        }

        // 2. Investimentos (upsert por idExterno)
        JsonNode investimentos = consultar(apiKey, "/investments?itemId=" + itemParam);
        Existentes<Investimento> existentesInv = indexarInvestimentos(itemId);
        Set<String> invVistos = new HashSet<>();
        int investCount = 0;
        if (investimentos.isArray()) {
            for (JsonNode invNode : investimentos) {
                String idExterno = invNode.path("id").asText("");
                if (idExterno.isBlank()) continue;
                Investimento inv = existentesInv.porId.getOrDefault(idExterno, new Investimento());
                inv.setItemId(itemId);
                inv.setIdExterno(idExterno);
                inv.setInstituicao(invNode.path("institution").asText("Corretora / Banco"));
                inv.setNome(invNode.path("name").asText("Aplicação"));
                inv.setTipo(invNode.path("type").asText("RENDA_FIXA"));
                inv.setValorInvestido(moeda(invNode.path("balance").asDouble(0.0)));
                inv.setRentabilidade(moeda(invNode.path("annualRate").asDouble(0.0)));
                inv.setAtualizadoEm(LocalDateTime.now(clock));
                investimentoRepository.save(inv);
                invVistos.add(idExterno);
                investCount++;
            }
        }
        removerInvestimentosAusentes(existentesInv, invVistos);

        return Map.of(
                "sucesso", true,
                "contasSincronizadas", contasCount,
                "investimentosSincronizados", investCount,
                "transacoesImportadas", transacoesCount,
                "origem", "PLUGGY_REAL"
        );
    }

    /** Linhas existentes de um item: com idExterno (indexadas) e legadas sem id (substituidas no upsert). */
    private static final class Existentes<T> {
        final Map<String, T> porId = new HashMap<>();
        final List<T> legadas = new ArrayList<>();
    }

    private Existentes<ContaConectada> indexarContas(String itemId) {
        Existentes<ContaConectada> mapa = new Existentes<>();
        for (ContaConectada c : contaRepository.findByItemId(itemId)) {
            if (c.getIdExterno() == null) mapa.legadas.add(c);
            else mapa.porId.put(c.getIdExterno(), c);
        }
        return mapa;
    }

    private Existentes<Investimento> indexarInvestimentos(String itemId) {
        Existentes<Investimento> mapa = new Existentes<>();
        for (Investimento i : investimentoRepository.findByItemId(itemId)) {
            if (i.getIdExterno() == null) mapa.legadas.add(i);
            else mapa.porId.put(i.getIdExterno(), i);
        }
        return mapa;
    }

    private void removerContasAusentes(Existentes<ContaConectada> existentes, Set<String> vistos) {
        existentes.porId.forEach((id, c) -> { if (!vistos.contains(id)) contaRepository.delete(c); });
        contaRepository.deleteAll(existentes.legadas);
    }

    private void removerInvestimentosAusentes(Existentes<Investimento> existentes, Set<String> vistos) {
        existentes.porId.forEach((id, i) -> { if (!vistos.contains(id)) investimentoRepository.delete(i); });
        investimentoRepository.deleteAll(existentes.legadas);
    }

    private int importarTransacoesConta(String apiKey, String accountId, String instituicao) throws Exception {
        LocalDate hoje = LocalDate.now(clock);
        String de = hoje.minusDays(30).toString();
        JsonNode txResults = consultar(apiKey,
                "/transactions?accountId=" + URLEncoder.encode(accountId, StandardCharsets.UTF_8) + "&from=" + de);
        int criadas = 0;
        if (txResults.isArray()) {
            for (JsonNode tx : txResults) {
                String desc = truncarDescricao(tx.path("description").asText("Transação bancária"));
                double amount = tx.path("amount").asDouble(0.0);
                String dateStr = tx.path("date").asText();
                LocalDate data = dateStr.length() >= 10 ? LocalDate.parse(dateStr.substring(0, 10)) : hoje;
                String txId = tx.path("id").asText("");
                String idExterno = txId.isBlank() ? null : "pluggy:" + txId;
                String tipo = amount < 0 ? "SAIDA" : "ENTRADA";
                BigDecimal valor = moeda(Math.abs(amount));
                if (importarLancamento(desc, valor, tipo, data, instituicao, idExterno)) criadas++;
            }
        }
        return criadas;
    }

    /**
     * Importa um lancamento de forma idempotente. Retorna true somente se criou uma linha nova.
     * 1) idExterno ja existente: ignora (preserva edicoes do usuario).
     * 2) Heuristica (descricao/valor/tipo/dia) apenas entre linhas ainda sem idExterno; se achar, so grava o id.
     */
    private boolean importarLancamento(String desc, BigDecimal valor, String tipo, LocalDate data, String conta, String idExterno) {
        if (valor.compareTo(BigDecimal.ZERO) <= 0) return false;
        if (idExterno != null && movimentacaoRepository.existsByIdExterno(idExterno)) return false;

        Optional<Movimentacao> semId = movimentacaoRepository.findByDataAndIdExternoIsNull(data).stream()
                .filter(m -> m.getDescricao().equalsIgnoreCase(desc)
                        && m.getValor().compareTo(valor) == 0
                        && m.getTipo().equalsIgnoreCase(tipo))
                .findFirst();
        if (semId.isPresent()) {
            if (idExterno != null) {
                Movimentacao existente = semId.get();
                existente.setIdExterno(idExterno);
                movimentacaoRepository.save(existente);
            }
            return false;
        }

        var catResult = geminiService.categorizar(desc);
        Movimentacao m = new Movimentacao();
        m.setDescricao(desc);
        m.setValor(valor);
        m.setData(data);
        m.setCategoria((String) catResult.getOrDefault("categoria", "Outros"));
        m.setTipo(tipo);
        m.setConta(conta);
        m.setRecorrente(false);
        m.setIdExterno(idExterno);
        movimentacaoRepository.save(m);
        return true;
    }

    private Map<String, Object> sincronizarMockSandbox(String itemId) {
        String prefixo = "sandbox:" + itemId + ":";
        LocalDateTime agora = LocalDateTime.now(clock);

        // 1. Contas e cartao (ids deterministicos)
        Existentes<ContaConectada> existentesContas = indexarContas(itemId);
        Set<String> contasVistas = new HashSet<>();
        upsertContaMock(existentesContas, contasVistas, itemId, prefixo + "c1", "Nubank", "Conta Corrente Nubank",
                "CORRENTE", new BigDecimal("3450.80"), null, null, agora);
        upsertContaMock(existentesContas, contasVistas, itemId, prefixo + "c2", "Nubank", "Cartão de Crédito Ultravioleta",
                "CARTAO_CREDITO", null, new BigDecimal("15000.00"), new BigDecimal("1820.45"), agora);
        upsertContaMock(existentesContas, contasVistas, itemId, prefixo + "c3", "Itaú", "Itaú Uniclass",
                "CORRENTE", new BigDecimal("1280.00"), null, null, agora);
        removerContasAusentes(existentesContas, contasVistas);

        // 2. Investimentos
        Existentes<Investimento> existentesInv = indexarInvestimentos(itemId);
        Set<String> invVistos = new HashSet<>();
        upsertInvestimentoMock(existentesInv, invVistos, itemId, prefixo + "i1", "Nubank / NuInvest", "Tesouro Selic 2029",
                "RENDA_FIXA", new BigDecimal("8500.00"), new BigDecimal("10.75"), agora);
        upsertInvestimentoMock(existentesInv, invVistos, itemId, prefixo + "i2", "Itaú", "CDB 110% CDI Liquidez Diária",
                "RENDA_FIXA", new BigDecimal("12000.00"), new BigDecimal("11.25"), agora);
        upsertInvestimentoMock(existentesInv, invVistos, itemId, prefixo + "i3", "Nubank / NuInvest", "IVVB11 — S&P 500",
                "ACOES", new BigDecimal("4300.00"), new BigDecimal("18.40"), agora);
        removerInvestimentosAusentes(existentesInv, invVistos);

        // 3. Transacoes simuladas categorizadas com IA (ids deterministicos sandbox:<itemId>:<n>)
        LocalDate hoje = LocalDate.now(clock);
        List<Map<String, Object>> txMocks = List.of(
                Map.of("desc", "iFood Restaurante delivery", "valor", new BigDecimal("74.90"), "tipo", "SAIDA", "conta", "Nubank"),
                Map.of("desc", "Uber *Trip Central", "valor", new BigDecimal("28.50"), "tipo", "SAIDA", "conta", "Nubank"),
                Map.of("desc", "Posto Ipiranga Combustivel", "valor", new BigDecimal("180.00"), "tipo", "SAIDA", "conta", "Itaú"),
                Map.of("desc", "Drogaria Raia Medicamentos", "valor", new BigDecimal("65.20"), "tipo", "SAIDA", "conta", "Nubank"),
                Map.of("desc", "Netflix Assinatura Mensal", "valor", new BigDecimal("55.90"), "tipo", "SAIDA", "conta", "Nubank")
        );
        int criadas = 0;
        for (int n = 0; n < txMocks.size(); n++) {
            var tx = txMocks.get(n);
            if (importarLancamento((String) tx.get("desc"), (BigDecimal) tx.get("valor"), (String) tx.get("tipo"),
                    hoje, (String) tx.get("conta"), prefixo + (n + 1))) {
                criadas++;
            }
        }

        return Map.of(
                "sucesso", true,
                "contasSincronizadas", contasVistas.size(),
                "investimentosSincronizados", invVistos.size(),
                "transacoesImportadas", criadas,
                "origem", "SANDBOX_SIMULADO"
        );
    }

    private void upsertContaMock(Existentes<ContaConectada> existentes, Set<String> vistos, String itemId, String idExterno,
                                 String instituicao, String nome, String tipo, BigDecimal saldo,
                                 BigDecimal limite, BigDecimal fatura, LocalDateTime agora) {
        ContaConectada c = existentes.porId.getOrDefault(idExterno, new ContaConectada());
        c.setItemId(itemId);
        c.setIdExterno(idExterno);
        c.setInstituicao(instituicao);
        c.setNome(nome);
        c.setTipo(tipo);
        c.setSaldo(saldo);
        c.setLimiteCredito(limite);
        c.setFaturaAtual(fatura);
        c.setAtualizadoEm(agora);
        contaRepository.save(c);
        vistos.add(idExterno);
    }

    private void upsertInvestimentoMock(Existentes<Investimento> existentes, Set<String> vistos, String itemId, String idExterno,
                                        String instituicao, String nome, String tipo, BigDecimal valor,
                                        BigDecimal rentabilidade, LocalDateTime agora) {
        Investimento inv = existentes.porId.getOrDefault(idExterno, new Investimento());
        inv.setItemId(itemId);
        inv.setIdExterno(idExterno);
        inv.setInstituicao(instituicao);
        inv.setNome(nome);
        inv.setTipo(tipo);
        inv.setValorInvestido(valor);
        inv.setRentabilidade(rentabilidade);
        inv.setAtualizadoEm(agora);
        investimentoRepository.save(inv);
        vistos.add(idExterno);
    }

    @Transactional
    public void removerItem(String itemId) {
        contaRepository.apagarPorItemId(itemId);
        investimentoRepository.apagarPorItemId(itemId);
        movimentacaoRepository.deleteAll(movimentacaoRepository.findByIdExternoStartingWith("sandbox:" + itemId + ":"));
    }
}
