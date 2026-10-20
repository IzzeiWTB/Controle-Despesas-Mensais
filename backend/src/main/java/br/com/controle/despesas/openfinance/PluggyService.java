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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class PluggyService {
    private static final Logger log = LoggerFactory.getLogger(PluggyService.class);

    @Value("${pluggy.client.id:${PLUGGY_CLIENT_ID:}}")
    private String clientId;

    @Value("${pluggy.client.secret:${PLUGGY_CLIENT_SECRET:}}")
    private String clientSecret;

    private final ContaConectadaRepository contaRepository;
    private final InvestimentoRepository investimentoRepository;
    private final MovimentacaoRepository movimentacaoRepository;
    private final GeminiService geminiService;
    private final ObjectMapper json;
    private final HttpClient httpClient;

    public PluggyService(ContaConectadaRepository contaRepository,
                         InvestimentoRepository investimentoRepository,
                         MovimentacaoRepository movimentacaoRepository,
                         GeminiService geminiService,
                         ObjectMapper json) {
        this.contaRepository = contaRepository;
        this.investimentoRepository = investimentoRepository;
        this.movimentacaoRepository = movimentacaoRepository;
        this.geminiService = geminiService;
        this.json = json;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isConfigurado() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
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
                    "erro", e.getMessage(),
                    "mensagem", "Falha ao conectar com a API do Pluggy. Use a sincronização simulada."
            );
        }
    }

    private String autenticarPluggy() throws Exception {
        Map<String, String> body = Map.of("clientId", clientId, "clientSecret", clientSecret);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.pluggy.ai/auth"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Falha na autenticação Pluggy: HTTP " + response.statusCode());
        }
        JsonNode root = json.readTree(response.body());
        return root.path("apiKey").asText();
    }

    private String solicitarConnectToken(String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.pluggy.ai/connect_token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-API-KEY", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Falha ao criar Connect Token Pluggy: HTTP " + response.statusCode());
        }
        JsonNode root = json.readTree(response.body());
        return root.path("accessToken").asText();
    }

    @Transactional
    public Map<String, Object> sincronizarItem(String itemId) {
        String idFinal = (itemId == null || itemId.isBlank()) ? "mock-pluggy-item" : itemId.trim();

        if (isConfigurado() && !idFinal.startsWith("mock-")) {
            try {
                return sincronizarComPluggyReal(idFinal);
            } catch (Exception e) {
                log.warn("Erro ao sincronizar via API do Pluggy: {}. Usando sincronizacao de simulacao.", e.getMessage());
            }
        }

        return sincronizarMockSandbox(idFinal);
    }

    private Map<String, Object> sincronizarComPluggyReal(String itemId) throws Exception {
        String apiKey = autenticarPluggy();

        // 1. Contas
        HttpRequest accReq = HttpRequest.newBuilder()
                .uri(URI.create("https://api.pluggy.ai/accounts?itemId=" + itemId))
                .timeout(Duration.ofSeconds(12))
                .header("X-API-KEY", apiKey)
                .GET().build();
        HttpResponse<String> accRes = httpClient.send(accReq, HttpResponse.BodyHandlers.ofString());

        int contasCount = 0;
        int transacoesCount = 0;

        if (accRes.statusCode() == 200) {
            contaRepository.deleteByItemId(itemId);
            JsonNode results = json.readTree(accRes.body()).path("results");
            if (results.isArray()) {
                for (JsonNode accNode : results) {
                    ContaConectada c = new ContaConectada();
                    c.setItemId(itemId);
                    c.setInstituicao(accNode.path("connector").path("name").asText("Banco Conectado"));
                    c.setNome(accNode.path("name").asText("Conta Bancária"));
                    String type = accNode.path("type").asText("BANK");
                    c.setTipo("CREDIT".equalsIgnoreCase(type) ? "CARTAO_CREDITO" : "CORRENTE");
                    c.setSaldo(BigDecimal.valueOf(accNode.path("balance").asDouble(0.0)));
                    if ("CREDIT".equalsIgnoreCase(type)) {
                        c.setLimiteCredito(BigDecimal.valueOf(accNode.path("creditData").path("creditLimit").asDouble(0.0)));
                        c.setFaturaAtual(BigDecimal.valueOf(accNode.path("creditData").path("balanceCloseDate").asDouble(0.0)));
                    }
                    c.setAtualizadoEm(LocalDateTime.now());
                    contaRepository.save(c);
                    contasCount++;

                    // Sincronizar transações da conta
                    String accountId = accNode.path("id").asText();
                    transacoesCount += importarTransacoesConta(apiKey, accountId, c.getInstituicao());
                }
            }
        }

        // 2. Investimentos
        HttpRequest invReq = HttpRequest.newBuilder()
                .uri(URI.create("https://api.pluggy.ai/investments?itemId=" + itemId))
                .timeout(Duration.ofSeconds(12))
                .header("X-API-KEY", apiKey)
                .GET().build();
        HttpResponse<String> invRes = httpClient.send(invReq, HttpResponse.BodyHandlers.ofString());

        int investCount = 0;
        if (invRes.statusCode() == 200) {
            investimentoRepository.deleteByItemId(itemId);
            JsonNode invResults = json.readTree(invRes.body()).path("results");
            if (invResults.isArray()) {
                for (JsonNode invNode : invResults) {
                    Investimento inv = new Investimento();
                    inv.setItemId(itemId);
                    inv.setInstituicao(invNode.path("institution").asText("Corretora / Banco"));
                    inv.setNome(invNode.path("name").asText("Aplicação"));
                    inv.setTipo(invNode.path("type").asText("RENDA_FIXA"));
                    inv.setValorInvestido(BigDecimal.valueOf(invNode.path("balance").asDouble(0.0)));
                    inv.setRentabilidade(BigDecimal.valueOf(invNode.path("annualRate").asDouble(0.0)));
                    inv.setAtualizadoEm(LocalDateTime.now());
                    investimentoRepository.save(inv);
                    investCount++;
                }
            }
        }

        return Map.of(
                "sucesso", true,
                "contasSincronizadas", contasCount,
                "investimentosSincronizados", investCount,
                "transacoesImportadas", transacoesCount,
                "origem", "PLUGGY_REAL"
        );
    }

    private int importarTransacoesConta(String apiKey, String accountId, String instituicao) {
        try {
            LocalDate hoje = LocalDate.now();
            String de = hoje.minusDays(30).toString();
            HttpRequest txReq = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.pluggy.ai/transactions?accountId=" + accountId + "&from=" + de))
                    .timeout(Duration.ofSeconds(12))
                    .header("X-API-KEY", apiKey)
                    .GET().build();
            HttpResponse<String> txRes = httpClient.send(txReq, HttpResponse.BodyHandlers.ofString());
            if (txRes.statusCode() != 200) return 0;

            JsonNode txResults = json.readTree(txRes.body()).path("results");
            int criadas = 0;
            if (txResults.isArray()) {
                for (JsonNode tx : txResults) {
                    String desc = tx.path("description").asText("Transação bancária");
                    double amount = tx.path("amount").asDouble(0.0);
                    String dateStr = tx.path("date").asText();
                    LocalDate data = dateStr.length() >= 10 ? LocalDate.parse(dateStr.substring(0, 10)) : hoje;

                    // Classificação com IA
                    var catResult = geminiService.categorizar(desc);
                    String categoria = (String) catResult.getOrDefault("categoria", "Outros");

                    String tipo = amount < 0 ? "SAIDA" : "ENTRADA";
                    BigDecimal valor = BigDecimal.valueOf(Math.abs(amount)).setScale(2, java.math.RoundingMode.HALF_UP);

                    // Evita duplicar se já existir idêntica no mesmo dia
                    boolean existe = movimentacaoRepository.findByDataBetweenOrderByDataDesc(data, data).stream().anyMatch(m ->
                            m.getDescricao().equalsIgnoreCase(desc) && m.getValor().compareTo(valor) == 0 && m.getTipo().equalsIgnoreCase(tipo));

                    if (!existe && valor.compareTo(BigDecimal.ZERO) > 0) {
                        Movimentacao m = new Movimentacao();
                        m.setDescricao(desc);
                        m.setValor(valor);
                        m.setData(data);
                        m.setCategoria(categoria);
                        m.setTipo(tipo);
                        m.setConta(instituicao);
                        m.setRecorrente(false);
                        movimentacaoRepository.save(m);
                        criadas++;
                    }
                }
            }
            return criadas;
        } catch (Exception e) {
            log.warn("Erro ao importar transacoes da conta {}: {}", accountId, e.getMessage());
            return 0;
        }
    }

    private Map<String, Object> sincronizarMockSandbox(String itemId) {
        contaRepository.deleteByItemId(itemId);
        investimentoRepository.deleteByItemId(itemId);

        // 1. Criar Contas e Cartão Sandbox
        ContaConectada nubankConta = new ContaConectada();
        nubankConta.setItemId(itemId);
        nubankConta.setInstituicao("Nubank");
        nubankConta.setNome("Conta Corrente Nubank");
        nubankConta.setTipo("CORRENTE");
        nubankConta.setSaldo(new BigDecimal("3450.80"));
        nubankConta.setAtualizadoEm(LocalDateTime.now());
        contaRepository.save(nubankConta);

        ContaConectada nubankCartao = new ContaConectada();
        nubankCartao.setItemId(itemId);
        nubankCartao.setInstituicao("Nubank");
        nubankCartao.setNome("Cartão de Crédito Ultravioleta");
        nubankCartao.setTipo("CARTAO_CREDITO");
        nubankCartao.setLimiteCredito(new BigDecimal("15000.00"));
        nubankCartao.setFaturaAtual(new BigDecimal("1820.45"));
        nubankCartao.setAtualizadoEm(LocalDateTime.now());
        contaRepository.save(nubankCartao);

        ContaConectada itauConta = new ContaConectada();
        itauConta.setItemId(itemId);
        itauConta.setInstituicao("Itaú");
        itauConta.setNome("Itaú Uniclass");
        itauConta.setTipo("CORRENTE");
        itauConta.setSaldo(new BigDecimal("1280.00"));
        itauConta.setAtualizadoEm(LocalDateTime.now());
        contaRepository.save(itauConta);

        // 2. Criar Investimentos Sandbox
        Investimento tesouro = new Investimento();
        tesouro.setItemId(itemId);
        tesouro.setInstituicao("Nubank / NuInvest");
        tesouro.setNome("Tesouro Selic 2029");
        tesouro.setTipo("RENDA_FIXA");
        tesouro.setValorInvestido(new BigDecimal("8500.00"));
        tesouro.setRentabilidade(new BigDecimal("10.75"));
        tesouro.setAtualizadoEm(LocalDateTime.now());
        investimentoRepository.save(tesouro);

        Investimento cdb = new Investimento();
        cdb.setItemId(itemId);
        cdb.setInstituicao("Itaú");
        cdb.setNome("CDB 110% CDI Liquidez Diária");
        cdb.setTipo("RENDA_FIXA");
        cdb.setValorInvestido(new BigDecimal("12000.00"));
        cdb.setRentabilidade(new BigDecimal("11.25"));
        cdb.setAtualizadoEm(LocalDateTime.now());
        investimentoRepository.save(cdb);

        Investimento fundoAcoes = new Investimento();
        fundoAcoes.setItemId(itemId);
        fundoAcoes.setInstituicao("Nubank / NuInvest");
        fundoAcoes.setNome("IVVB11 — S&P 500");
        fundoAcoes.setTipo("ACOES");
        fundoAcoes.setValorInvestido(new BigDecimal("4300.00"));
        fundoAcoes.setRentabilidade(new BigDecimal("18.40"));
        fundoAcoes.setAtualizadoEm(LocalDateTime.now());
        investimentoRepository.save(fundoAcoes);

        // 3. Importar transações mock categorizadas com IA
        LocalDate hoje = LocalDate.now();
        List<Map<String, Object>> txMocks = List.of(
                Map.of("desc", "iFood Restaurante delivery", "valor", new BigDecimal("74.90"), "tipo", "SAIDA", "conta", "Nubank"),
                Map.of("desc", "Uber *Trip Central", "valor", new BigDecimal("28.50"), "tipo", "SAIDA", "conta", "Nubank"),
                Map.of("desc", "Posto Ipiranga Combustivel", "valor", new BigDecimal("180.00"), "tipo", "SAIDA", "conta", "Itaú"),
                Map.of("desc", "Drogaria Raia Medicamentos", "valor", new BigDecimal("65.20"), "tipo", "SAIDA", "conta", "Nubank"),
                Map.of("desc", "Netflix Assinatura Mensal", "valor", new BigDecimal("55.90"), "tipo", "SAIDA", "conta", "Nubank")
        );

        int criadas = 0;
        for (var tx : txMocks) {
            String desc = (String) tx.get("desc");
            BigDecimal val = (BigDecimal) tx.get("valor");
            String tipo = (String) tx.get("tipo");
            String conta = (String) tx.get("conta");

            boolean jaExiste = movimentacaoRepository.findByDataBetweenOrderByDataDesc(hoje.withDayOfMonth(1), hoje).stream()
                    .anyMatch(m -> m.getDescricao().equalsIgnoreCase(desc) && m.getValor().compareTo(val) == 0);

            if (!jaExiste) {
                var catRes = geminiService.categorizar(desc);
                Movimentacao m = new Movimentacao();
                m.setDescricao(desc);
                m.setValor(val);
                m.setData(hoje);
                m.setCategoria((String) catRes.getOrDefault("categoria", "Outros"));
                m.setTipo(tipo);
                m.setConta(conta);
                m.setRecorrente(false);
                movimentacaoRepository.save(m);
                criadas++;
            }
        }

        return Map.of(
                "sucesso", true,
                "contasSincronizadas", 3,
                "investimentosSincronizados", 3,
                "transacoesImportadas", criadas,
                "origem", "SANDBOX_SIMULADO"
        );
    }

    @Transactional
    public void removerItem(String itemId) {
        contaRepository.deleteByItemId(itemId);
        investimentoRepository.deleteByItemId(itemId);
    }
}

