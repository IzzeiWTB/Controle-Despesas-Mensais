package br.com.controle.despesas.openfinance;

import br.com.controle.despesas.Movimentacao;
import br.com.controle.despesas.MovimentacaoRepository;
import br.com.controle.despesas.importacao.CategorizadorLancamentos;
import br.com.controle.despesas.importacao.ExtratoParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

@Service
public class PluggyService {
    private static final Logger log = LoggerFactory.getLogger(PluggyService.class);
    static final int TAMANHO_MAXIMO_DESCRICAO = 100;
    private static final Pattern ITEM_ID_VALIDO = Pattern.compile("[A-Za-z0-9_-]{8,100}");
    private static final int MAXIMO_PAGINAS = 40;
    private static final Duration INTERVALO_SINCRONIZACAO_AUTOMATICA = Duration.ofHours(6);
    private static final Duration ESPERA_SINCRONIZACAO_EM_ANDAMENTO = Duration.ofMinutes(3);
    private final java.util.concurrent.ConcurrentMap<String, ReentrantLock> travasPorItem = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${pluggy.client.id:${PLUGGY_CLIENT_ID:}}")
    private String clientId;

    @Value("${pluggy.client.secret:${PLUGGY_CLIENT_SECRET:}}")
    private String clientSecret;

    @Value("${pluggy.api.url:https://api.pluggy.ai}")
    private String apiUrl;

    /** Janela de transacoes na primeira sincronizacao de um item e nas seguintes. */
    @Value("${pluggy.dias-primeira-sincronizacao:90}")
    private int diasPrimeiraSincronizacao;

    @Value("${pluggy.dias-sincronizacao:35}")
    private int diasSincronizacao;

    private final ContaConectadaRepository contaRepository;
    private final InvestimentoRepository investimentoRepository;
    private final MovimentacaoRepository movimentacaoRepository;
    private final ItemPluggyRepository itemRepository;
    private final CategorizadorLancamentos categorizador;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate transacao;
    private final HttpClient httpClient;

    public PluggyService(ContaConectadaRepository contaRepository,
                         InvestimentoRepository investimentoRepository,
                         MovimentacaoRepository movimentacaoRepository,
                         ItemPluggyRepository itemRepository,
                         CategorizadorLancamentos categorizador,
                         ObjectMapper json,
                         Clock clock,
                         PlatformTransactionManager transactionManager) {
        this.contaRepository = contaRepository;
        this.investimentoRepository = investimentoRepository;
        this.movimentacaoRepository = movimentacaoRepository;
        this.itemRepository = itemRepository;
        this.categorizador = categorizador;
        this.json = json;
        this.clock = clock;
        this.transacao = new TransactionTemplate(transactionManager);
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
        return limitar(descricao, TAMANHO_MAXIMO_DESCRICAO);
    }

    private static String limitar(String texto, int maximo) {
        if (texto == null) return "";
        texto = limpar(texto);
        return texto.length() > maximo ? texto.substring(0, maximo) : texto;
    }

    /** Remove caracteres de controle (o PostgreSQL rejeita \u0000 em texto) que alguns bancos mandam na descricao. */
    static String limpar(String texto) {
        return texto == null ? "" : texto.replaceAll("\\p{Cntrl}", " ");
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
            throw new PluggyIndisponivelException("Falha na autenticação Pluggy: HTTP " + response.statusCode()
                    + ". Confira PLUGGY_CLIENT_ID e PLUGGY_CLIENT_SECRET no servidor.");
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

    // ---------- itens vinculados (Meu Pluggy / Pluggy Connect) ----------

    public List<ItemPluggy> listarItens() {
        return itemRepository.findAllByOrderByCriadoEmAsc();
    }

    /** Vincula um item real (ex.: copiado do painel Pluggy para uma conexao Meu Pluggy) e sincroniza em seguida. */
    public Map<String, Object> vincularItem(String itemId, String nome) {
        String id = itemId == null ? "" : itemId.trim();
        if (!ITEM_ID_VALIDO.matcher(id).matches() || ehItemSimulado(id)) {
            throw new IllegalArgumentException("Item inválido. Copie o Item ID da conexão no painel da Pluggy.");
        }
        String apelido = nome == null ? "" : nome.trim();
        if (apelido.length() > 60) throw new IllegalArgumentException("O nome deve ter até 60 caracteres.");
        if (!isConfigurado()) {
            throw new PluggyIndisponivelException("Configure PLUGGY_CLIENT_ID e PLUGGY_CLIENT_SECRET no servidor (Render) antes de vincular o banco.");
        }
        ItemPluggy item = itemRepository.findById(id).orElseGet(() -> new ItemPluggy(id, LocalDateTime.now(clock)));
        if (!apelido.isEmpty()) item.setNome(apelido);
        itemRepository.save(item);
        return sincronizarRegistrandoErro(id);
    }

    /** Sincroniza o item e, se falhar, guarda a mensagem no item vinculado para exibir na tela. */
    public Map<String, Object> sincronizarRegistrandoErro(String itemId) {
        try {
            return sincronizarItem(itemId);
        } catch (RuntimeException e) {
            registrarErro(itemId, mensagemErro(e));
            throw e;
        }
    }

    /**
     * Mensagem exibida no item: erros da Pluggy como vieram; falhas de gravacao com a causa mais especifica
     * (primeira linha, sem o "Detail" do PostgreSQL), para diagnosticar sem acesso aos logs.
     */
    static String mensagemErro(RuntimeException e) {
        if (e instanceof PluggyIndisponivelException || e instanceof IllegalArgumentException) return limitar(e.getMessage(), 255);
        log.warn("Falha ao gravar sincronizacao Pluggy", e);
        Throwable causa = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(e);
        String detalhe = causa.getMessage() == null ? causa.getClass().getSimpleName()
                : causa.getMessage().lines().findFirst().orElse("").trim();
        return limitar("Erro ao gravar os dados sincronizados: " + detalhe, 255);
    }

    /**
     * Sincroniza todos os itens vinculados. Com apenasDesatualizados, pula itens sincronizados nas ultimas 6 horas
     * (usado ao abrir o app; o Meu Pluggy atualiza os dados dos bancos a cada 24 horas).
     */
    public Map<String, Object> sincronizarTodos(boolean apenasDesatualizados) {
        LocalDateTime limite = LocalDateTime.now(clock).minus(INTERVALO_SINCRONIZACAO_AUTOMATICA);
        List<Map<String, Object>> resultados = new ArrayList<>();
        int transacoes = 0, sincronizados = 0, falhas = 0;
        for (ItemPluggy item : itemRepository.findAllByOrderByCriadoEmAsc()) {
            if (apenasDesatualizados && item.getUltimaSincronizacao() != null && item.getUltimaSincronizacao().isAfter(limite)) continue;
            Map<String, Object> linha = new LinkedHashMap<>();
            linha.put("itemId", item.getItemId());
            linha.put("nome", item.getRotulo());
            try {
                Map<String, Object> r = sincronizarRegistrandoErro(item.getItemId());
                int importadas = (int) r.getOrDefault("transacoesImportadas", 0);
                transacoes += importadas;
                sincronizados++;
                linha.put("ok", true);
                linha.put("transacoesImportadas", importadas);
            } catch (RuntimeException e) {
                falhas++;
                linha.put("ok", false);
                linha.put("erro", itemRepository.findById(item.getItemId()).map(ItemPluggy::getUltimoErro).orElseGet(() -> mensagemErro(e)));
            }
            resultados.add(linha);
        }
        return Map.of("itens", resultados, "transacoesImportadas", transacoes,
                "sincronizados", sincronizados, "falhas", falhas);
    }

    private void registrarErro(String itemId, String mensagem) {
        itemRepository.findById(itemId).ifPresent(item -> {
            item.setUltimoErro(mensagem);
            itemRepository.save(item);
        });
    }

    // ---------- sincronizacao ----------

    /**
     * Sincroniza um item. Itens "mock*"/"sandbox*" usam dados simulados explicitamente;
     * qualquer outro item exige a Pluggy real e, se ela falhar, o erro e propagado (sem dados ficticios).
     * Chamadas HTTP e categorizacao (inclusive Gemini) ficam fora da transacao; so a gravacao e transacional.
     */
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
        // Uma sincronizacao por item de cada vez: a automatica (ao abrir o app) e o botao "Sincronizar todos"
        // gravariam as mesmas transacoes em paralelo e a segunda violaria o UNIQUE de id_externo.
        // A segunda espera a primeira e encontra tudo ja importado.
        ReentrantLock trava = travasPorItem.computeIfAbsent(idFinal, k -> new ReentrantLock());
        boolean travado = false;
        try {
            travado = trava.tryLock(ESPERA_SINCRONIZACAO_EM_ANDAMENTO.toSeconds(), TimeUnit.SECONDS);
            if (!travado) {
                throw new PluggyIndisponivelException("Este banco já está sendo sincronizado; tente de novo em instantes.");
            }
            return sincronizarComPluggyReal(idFinal);
        } catch (PluggyIndisponivelException | DataAccessException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PluggyIndisponivelException("Sincronização com a Pluggy interrompida.", e);
        } catch (Exception e) {
            log.warn("Erro ao sincronizar via API do Pluggy: {}", e.getMessage());
            throw new PluggyIndisponivelException("Falha ao sincronizar com a Pluggy: " + e.getMessage(), e);
        } finally {
            if (travado) trava.unlock();
        }
    }

    private JsonNode consultarRaiz(String apiKey, String caminho) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base() + caminho))
                .timeout(Duration.ofSeconds(20))
                .header("X-API-KEY", apiKey)
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String motivo = "";
            try {
                JsonNode erro = json.readTree(response.body());
                motivo = primeiroPreenchido(erro.path("code").asText(""), erro.path("message").asText(""), "");
            } catch (Exception ignorada) {
                // corpo sem JSON: fica so o status
            }
            throw new PluggyIndisponivelException("A Pluggy respondeu HTTP " + response.statusCode() + " em " + caminho.split("\\?")[0]
                    + (motivo.isBlank() ? "" : " (" + limitar(motivo, 80) + ")"));
        }
        return json.readTree(response.body());
    }

    /** Percorre todas as paginas (pageSize maximo da Pluggy: 500). */
    private List<JsonNode> consultarTodas(String apiKey, String caminho) throws Exception {
        List<JsonNode> resultados = new ArrayList<>();
        int pagina = 1, totalPaginas;
        do {
            JsonNode raiz = consultarRaiz(apiKey, caminho + (caminho.contains("?") ? "&" : "?") + "pageSize=500&page=" + pagina);
            raiz.path("results").forEach(resultados::add);
            totalPaginas = raiz.path("totalPages").asInt(1);
            pagina++;
        } while (pagina <= totalPaginas && pagina <= MAXIMO_PAGINAS);
        return resultados;
    }

    /**
     * Transacoes pela API v2 (cursor). O GET /transactions paginado foi descontinuado pela Pluggy
     * e responde 410 ENDPOINT_DEPRECATED para chaves novas. O campo "next" e a query string pronta
     * da proxima pagina (opaca) ou null na ultima.
     */
    private List<JsonNode> consultarTransacoes(String apiKey, String accountId, LocalDate de) throws Exception {
        final String endpoint = "/v2/transactions";
        List<JsonNode> resultados = new ArrayList<>();
        String caminho = endpoint + "?accountId=" + URLEncoder.encode(accountId, StandardCharsets.UTF_8) + "&dateFrom=" + de;
        for (int pagina = 1; caminho != null && pagina <= MAXIMO_PAGINAS; pagina++) {
            JsonNode raiz = consultarRaiz(apiKey, caminho);
            raiz.path("results").forEach(resultados::add);
            String next = raiz.path("next").isTextual() ? raiz.path("next").asText().trim() : "";
            if (next.isEmpty()) caminho = null;
            else if (next.startsWith("/")) caminho = next;
            else if (next.startsWith("?")) caminho = endpoint + next;
            else if (next.startsWith("http")) caminho = URI.create(next).getRawPath() + "?" + URI.create(next).getRawQuery();
            else caminho = endpoint + "?" + next;
        }
        return resultados;
    }

    private record DetalheItem(String instituicao, String status, LocalDateTime consentimentoExpiraEm) {}

    private record ContaPluggy(String id, boolean credito, String nome, String rotulo,
                               BigDecimal saldo, BigDecimal limite, BigDecimal fatura) {}

    record TransacaoPluggy(String idExterno, String descricao, BigDecimal valor, String tipo, LocalDate data, String conta) {}

    private record InvestimentoPluggy(String id, String instituicao, String nome, String tipo, BigDecimal valor, BigDecimal rentabilidade) {}

    private Map<String, Object> sincronizarComPluggyReal(String itemId) throws Exception {
        LocalDateTime agora = LocalDateTime.now(clock);
        ItemPluggy registro = itemRepository.findById(itemId).orElse(null);
        boolean primeira = registro == null || registro.getUltimaSincronizacao() == null;
        LocalDate de = LocalDate.now(clock).minusDays(primeira ? diasPrimeiraSincronizacao : diasSincronizacao);

        // 1. Leitura na Pluggy (fora de transacao)
        String apiKey = autenticarPluggy();
        String itemParam = URLEncoder.encode(itemId, StandardCharsets.UTF_8);
        DetalheItem detalhe = consultarItem(apiKey, itemParam);
        JsonNode contasJson = consultarRaiz(apiKey, "/accounts?itemId=" + itemParam).path("results");

        String instituicao = primeiroPreenchido(
                registro != null ? registro.getNome() : null,
                detalhe.instituicao(),
                contasJson.path(0).path("connector").path("name").asText(""),
                "Banco");

        // Ids repetidos na resposta violariam o UNIQUE (item_id, id_externo); fica o primeiro.
        Set<String> contasLidas = new HashSet<>();
        List<ContaPluggy> contas = new ArrayList<>();
        for (JsonNode acc : contasJson) {
            String id = acc.path("id").asText("");
            if (id.isBlank() || !contasLidas.add(id)) continue;
            boolean credito = "CREDIT".equalsIgnoreCase(acc.path("type").asText("BANK"));
            String nome = primeiroPreenchido(acc.path("marketingName").asText(""), acc.path("name").asText(""), "Conta");
            BigDecimal balance = moeda(acc.path("balance").asDouble(0.0));
            contas.add(new ContaPluggy(id, credito, nome, rotuloConta(instituicao, credito, acc.path("number").asText("")),
                    credito ? null : balance,
                    credito ? moeda(acc.path("creditData").path("creditLimit").asDouble(0.0)) : null,
                    credito ? balance : null));
        }

        List<TransacaoPluggy> transacoes = new ArrayList<>();
        int ignoradas = 0;
        for (ContaPluggy conta : contas) {
            for (JsonNode tx : consultarTransacoes(apiKey, conta.id(), de)) {
                TransacaoPluggy t = lerTransacao(tx, conta.credito(), conta.rotulo(), clock.getZone());
                if (t == null) ignoradas++;
                else transacoes.add(t);
            }
        }

        Set<String> investimentosLidos = new HashSet<>();
        List<InvestimentoPluggy> investimentos = new ArrayList<>();
        for (JsonNode inv : consultarTodas(apiKey, "/investments?itemId=" + itemParam)) {
            String id = inv.path("id").asText("");
            double saldo = inv.path("balance").asDouble(0.0);
            if (id.isBlank() || moeda(saldo).signum() <= 0 || !investimentosLidos.add(id)
                    || "TOTAL_WITHDRAWAL".equalsIgnoreCase(inv.path("status").asText(""))) continue;
            JsonNode inst = inv.path("institution");
            investimentos.add(new InvestimentoPluggy(id,
                    limitar(primeiroPreenchido(inst.isObject() ? inst.path("name").asText("") : inst.asText(""), instituicao), 80),
                    limitar(primeiroPreenchido(inv.path("name").asText(""), "Aplicação"), 120),
                    tipoInvestimento(inv.path("type").asText("")),
                    moeda(saldo),
                    inv.hasNonNull("annualRate") ? moeda(inv.path("annualRate").asDouble()) : null));
        }

        // 2. Categorizacao (fora de transacao; Gemini so para lancamentos ainda nao importados)
        Set<String> jaImportados = idsExistentes(transacoes.stream().map(TransacaoPluggy::idExterno).filter(Objects::nonNull).toList());
        List<String> categorias = categorizador.categorizar(
                transacoes.stream().map(TransacaoPluggy::descricao).toList(),
                transacoes.stream().map(TransacaoPluggy::tipo).toList(),
                i -> transacoes.get(i).idExterno() == null || !jaImportados.contains(transacoes.get(i).idExterno()));

        // 3. Gravacao atomica
        final int totalIgnoradas = ignoradas;
        return transacao.execute(status -> {
            Existentes<ContaConectada> existentesContas = indexarContas(itemId);
            Set<String> contasVistas = new HashSet<>();
            for (ContaPluggy cp : contas) {
                ContaConectada c = existentesContas.porId.getOrDefault(cp.id(), new ContaConectada());
                c.setItemId(itemId);
                c.setIdExterno(cp.id());
                c.setInstituicao(limitar(instituicao, 80));
                c.setNome(limitar(cp.nome(), 100));
                c.setTipo(cp.credito() ? "CARTAO_CREDITO" : "CORRENTE");
                c.setSaldo(cp.saldo());
                c.setLimiteCredito(cp.limite());
                c.setFaturaAtual(cp.fatura());
                c.setAtualizadoEm(agora);
                contaRepository.save(c);
                contasVistas.add(cp.id());
            }
            removerContasAusentes(existentesContas, contasVistas);

            Importador importador = new Importador(jaImportados);
            int criadas = 0;
            for (int i = 0; i < transacoes.size(); i++) {
                TransacaoPluggy t = transacoes.get(i);
                if (importador.importar(t.descricao(), t.valor(), t.tipo(), t.data(), t.conta(), t.idExterno(), categorias.get(i))) criadas++;
            }

            Existentes<Investimento> existentesInv = indexarInvestimentos(itemId);
            Set<String> invVistos = new HashSet<>();
            for (InvestimentoPluggy ip : investimentos) {
                Investimento inv = existentesInv.porId.getOrDefault(ip.id(), new Investimento());
                inv.setItemId(itemId);
                inv.setIdExterno(ip.id());
                inv.setInstituicao(ip.instituicao());
                inv.setNome(ip.nome());
                inv.setTipo(ip.tipo());
                inv.setValorInvestido(ip.valor());
                inv.setRentabilidade(ip.rentabilidade());
                inv.setAtualizadoEm(agora);
                investimentoRepository.save(inv);
                invVistos.add(ip.id());
            }
            removerInvestimentosAusentes(existentesInv, invVistos);

            ItemPluggy item = itemRepository.findById(itemId).orElseGet(() -> new ItemPluggy(itemId, agora));
            item.setInstituicao(limitar(instituicao, 80));
            item.setStatusPluggy(detalhe.status().isBlank() ? null : limitar(detalhe.status(), 40));
            item.setConsentimentoExpiraEm(detalhe.consentimentoExpiraEm());
            item.setUltimaSincronizacao(agora);
            item.setUltimoErro(null);
            itemRepository.save(item);

            return Map.of(
                    "sucesso", true,
                    "contasSincronizadas", contas.size(),
                    "investimentosSincronizados", investimentos.size(),
                    "transacoesImportadas", criadas,
                    "transacoesIgnoradas", totalIgnoradas,
                    "instituicao", instituicao,
                    "origem", "PLUGGY_REAL"
            );
        });
    }

    /** Nome do banco e validade da autorizacao; falha aqui nao impede a sincronizacao. */
    private DetalheItem consultarItem(String apiKey, String itemParam) {
        try {
            JsonNode item = consultarRaiz(apiKey, "/items/" + itemParam);
            String conector = item.path("connector").path("name").asText("");
            // Conector agregador (ex.: "MeuPluggy") nao e o nome do banco.
            if (conector.toLowerCase(Locale.ROOT).contains("pluggy")) conector = "";
            LocalDateTime expira = null;
            String bruto = item.path("consentExpiresAt").asText("");
            if (!bruto.isBlank()) {
                try { expira = OffsetDateTime.parse(bruto).atZoneSameInstant(clock.getZone()).toLocalDateTime(); }
                catch (DateTimeParseException ignored) { /* campo opcional */ }
            }
            return new DetalheItem(conector, item.path("status").asText(""), expira);
        } catch (Exception e) {
            log.debug("Detalhe do item Pluggy indisponivel: {}", e.getMessage());
            return new DetalheItem("", "", null);
        }
    }

    /**
     * Converte uma transacao da Pluggy; retorna null para as que nao devem virar lancamento
     * (valor zero, pagamento de fatura de cartao, transferencia entre contas do proprio titular).
     */
    static TransacaoPluggy lerTransacao(JsonNode tx, boolean cartao, String conta, ZoneId zona) {
        String descricao = truncarDescricao(primeiroPreenchido(tx.path("description").asText(""), "Transação bancária"));
        JsonNode valorNode = tx.hasNonNull("amountInAccountCurrency") ? tx.get("amountInAccountCurrency") : tx.path("amount");
        double amount = valorNode.asDouble(0.0);
        BigDecimal valor = moeda(Math.abs(amount));
        if (valor.signum() == 0) return null;
        String categoriaPluggy = ExtratoParser.normalizar(tx.path("category").asText(""));
        if (CategorizadorLancamentos.ehPagamentoFatura(descricao)
                || categoriaPluggy.contains("credit card payment")
                || categoriaPluggy.contains("same person transfer")) {
            return null;
        }
        LocalDate data = dataTransacao(tx.path("date").asText(""), zona);
        if (data == null) return null;
        String id = tx.path("id").asText("");
        return new TransacaoPluggy(id.isBlank() ? null : "pluggy:" + id, descricao, valor,
                tipoTransacao(tx.path("type").asText(""), cartao, amount), data, conta);
    }

    /**
     * DEBIT = saida e CREDIT = entrada. Sem o campo type, usa o sinal: no cartao a Pluggy envia compras
     * positivas e pagamentos/estornos negativos; em contas, saidas sao negativas.
     */
    static String tipoTransacao(String type, boolean cartao, double amount) {
        if ("DEBIT".equalsIgnoreCase(type)) return "SAIDA";
        if ("CREDIT".equalsIgnoreCase(type)) return "ENTRADA";
        if (cartao) return amount > 0 ? "SAIDA" : "ENTRADA";
        return amount < 0 ? "SAIDA" : "ENTRADA";
    }

    /**
     * A Pluggy envia datas em UTC. Meia-noite UTC representa apenas o dia (sem horario) e e mantida;
     * com horario, converte para o fuso do usuario (compra as 22h em Brasilia nao vira o dia seguinte).
     */
    static LocalDate dataTransacao(String bruto, ZoneId zona) {
        if (bruto == null || bruto.length() < 10) return null;
        try {
            OffsetDateTime instante = OffsetDateTime.parse(bruto);
            if (instante.toLocalTime().equals(LocalTime.MIDNIGHT)) return instante.toLocalDate();
            return instante.atZoneSameInstant(zona).toLocalDate();
        } catch (DateTimeParseException e) {
            try { return LocalDate.parse(bruto.substring(0, 10)); } catch (DateTimeParseException ignored) { return null; }
        }
    }

    static String tipoInvestimento(String type) {
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "FIXED_INCOME" -> "RENDA_FIXA";
            case "EQUITY", "ETF" -> "ACOES";
            case "MUTUAL_FUND" -> "FUNDOS";
            default -> "OUTROS";
        };
    }

    /** Rotulo da conta nos lancamentos: "Nubank" para conta e "Nubank · cartão 4177" para cartao. */
    static String rotuloConta(String instituicao, boolean cartao, String numero) {
        if (!cartao) return limitar(instituicao, 60);
        String digitos = numero == null ? "" : numero.replaceAll("\\D", "");
        String final4 = digitos.length() >= 4 ? " " + digitos.substring(digitos.length() - 4) : "";
        return limitar(instituicao + " · cartão" + final4, 60);
    }

    private static String primeiroPreenchido(String... valores) {
        for (String v : valores) if (v != null && !limpar(v).isBlank()) return limpar(v).trim();
        return "";
    }

    private Set<String> idsExistentes(List<String> ids) {
        Set<String> encontrados = new HashSet<>();
        for (int i = 0; i < ids.size(); i += 500) {
            for (Movimentacao m : movimentacaoRepository.findByIdExternoIn(ids.subList(i, Math.min(ids.size(), i + 500)))) {
                encontrados.add(m.getIdExterno());
            }
        }
        return encontrados;
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

    /**
     * Grava lancamentos de forma idempotente dentro de uma transacao, com cache por sincronizacao.
     * 1) idExterno ja existente: ignora (preserva edicoes do usuario).
     * 2) Heuristica (descricao/valor/tipo/dia) apenas entre linhas ainda sem idExterno; se achar, so grava o id.
     */
    private final class Importador {
        private final Set<String> existentes;
        private final Map<LocalDate, List<Movimentacao>> semIdPorDia = new HashMap<>();

        Importador(Set<String> existentes) {
            this.existentes = new HashSet<>(existentes);
        }

        boolean importar(String desc, BigDecimal valor, String tipo, LocalDate data, String conta, String idExterno, String categoria) {
            if (valor.compareTo(BigDecimal.ZERO) <= 0) return false;
            if (idExterno != null && existentes.contains(idExterno)) return false;

            List<Movimentacao> semId = semIdPorDia.computeIfAbsent(data,
                    d -> new ArrayList<>(movimentacaoRepository.findByDataAndIdExternoIsNull(d)));
            Optional<Movimentacao> legado = semId.stream()
                    .filter(m -> m.getDescricao().equalsIgnoreCase(desc)
                            && m.getValor().compareTo(valor) == 0
                            && m.getTipo().equalsIgnoreCase(tipo))
                    .findFirst();
            if (legado.isPresent()) {
                semId.remove(legado.get());
                if (idExterno != null) {
                    legado.get().setIdExterno(idExterno);
                    movimentacaoRepository.save(legado.get());
                    existentes.add(idExterno);
                }
                return false;
            }

            Movimentacao m = new Movimentacao();
            m.setDescricao(desc);
            m.setValor(valor);
            m.setData(data);
            m.setCategoria(categoria == null || categoria.isBlank() ? "Outros" : limitar(categoria, 60));
            m.setTipo(tipo);
            m.setConta(limitar(conta, 60));
            m.setRecorrente(false);
            m.setIdExterno(idExterno);
            movimentacaoRepository.save(m);
            if (idExterno != null) existentes.add(idExterno);
            return true;
        }
    }

    private Map<String, Object> sincronizarMockSandbox(String itemId) {
        String prefixo = "sandbox:" + itemId + ":";
        LocalDateTime agora = LocalDateTime.now(clock);
        LocalDate hoje = LocalDate.now(clock);
        List<TransacaoPluggy> txMocks = List.of(
                new TransacaoPluggy(prefixo + 1, "iFood Restaurante delivery", new BigDecimal("74.90"), "SAIDA", hoje, "Nubank"),
                new TransacaoPluggy(prefixo + 2, "Uber *Trip Central", new BigDecimal("28.50"), "SAIDA", hoje, "Nubank"),
                new TransacaoPluggy(prefixo + 3, "Posto Ipiranga Combustivel", new BigDecimal("180.00"), "SAIDA", hoje, "Itaú"),
                new TransacaoPluggy(prefixo + 4, "Drogaria Raia Medicamentos", new BigDecimal("65.20"), "SAIDA", hoje, "Nubank"),
                new TransacaoPluggy(prefixo + 5, "Netflix Assinatura Mensal", new BigDecimal("55.90"), "SAIDA", hoje, "Nubank")
        );
        Set<String> jaImportados = idsExistentes(txMocks.stream().map(TransacaoPluggy::idExterno).toList());
        List<String> categorias = categorizador.categorizar(
                txMocks.stream().map(TransacaoPluggy::descricao).toList(),
                txMocks.stream().map(TransacaoPluggy::tipo).toList(),
                i -> !jaImportados.contains(txMocks.get(i).idExterno()));

        return transacao.execute(status -> {
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

            // 3. Transacoes simuladas (ids deterministicos sandbox:<itemId>:<n>)
            Importador importador = new Importador(jaImportados);
            int criadas = 0;
            for (int n = 0; n < txMocks.size(); n++) {
                TransacaoPluggy t = txMocks.get(n);
                if (importador.importar(t.descricao(), t.valor(), t.tipo(), t.data(), t.conta(), t.idExterno(), categorias.get(n))) {
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
        });
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

    /** Desvincula o item: remove contas, investimentos e o registro; lancamentos reais importados sao mantidos. */
    public void removerItem(String itemId) {
        transacao.executeWithoutResult(status -> {
            contaRepository.apagarPorItemId(itemId);
            investimentoRepository.apagarPorItemId(itemId);
            movimentacaoRepository.deleteAll(movimentacaoRepository.findByIdExternoStartingWith("sandbox:" + itemId + ":"));
            itemRepository.findById(itemId).ifPresent(itemRepository::delete);
        });
    }

    /** Lancamentos que o antigo modo simulado gravava sem id externo (descricao, valor, conta). */
    private static final List<TransacaoPluggy> LANCAMENTOS_SIMULADOS_LEGADOS = List.of(
            new TransacaoPluggy(null, "iFood Restaurante delivery", new BigDecimal("74.90"), "SAIDA", null, "Nubank"),
            new TransacaoPluggy(null, "Uber *Trip Central", new BigDecimal("28.50"), "SAIDA", null, "Nubank"),
            new TransacaoPluggy(null, "Posto Ipiranga Combustivel", new BigDecimal("180.00"), "SAIDA", null, "Itaú"),
            new TransacaoPluggy(null, "Drogaria Raia Medicamentos", new BigDecimal("65.20"), "SAIDA", null, "Nubank"),
            new TransacaoPluggy(null, "Netflix Assinatura Mensal", new BigDecimal("55.90"), "SAIDA", null, "Nubank"));

    /** Rotulo de conta que a antiga sincronizacao (so alcancava o banco de teste da Pluggy) gravava. */
    private static final String CONTA_LEGADA_TESTE = "Banco Conectado";

    /**
     * Remove dados de teste: contas e investimentos que nao pertencem a nenhum banco vinculado (simulacao/Sandbox
     * e o antigo Pluggy Connect de teste) e lancamentos ficticios (ids "sandbox:", os cinco do antigo modo simulado
     * gravados sem id e os da conta "Banco Conectado"). Sem confirmar, apenas lista o que seria removido.
     */
    public Map<String, Object> limparDadosDeTeste(boolean confirmar) {
        Set<String> vinculados = new HashSet<>();
        itemRepository.findAll().forEach(i -> vinculados.add(i.getItemId()));
        java.util.function.Predicate<String> deTeste = itemId -> itemId == null || ehItemSimulado(itemId) || !vinculados.contains(itemId);

        List<ContaConectada> contasTeste = contaRepository.findAll().stream().filter(c -> deTeste.test(c.getItemId())).toList();
        List<Investimento> investimentosTeste = investimentoRepository.findAll().stream().filter(i -> deTeste.test(i.getItemId())).toList();

        Map<java.util.UUID, Movimentacao> lancamentos = new LinkedHashMap<>();
        movimentacaoRepository.findByIdExternoStartingWith("sandbox:").forEach(m -> lancamentos.put(m.getId(), m));
        movimentacaoRepository.findByIdExternoIsNullAndDescricaoIn(
                        LANCAMENTOS_SIMULADOS_LEGADOS.stream().map(TransacaoPluggy::descricao).toList()).stream()
                .filter(m -> LANCAMENTOS_SIMULADOS_LEGADOS.stream().anyMatch(s -> s.descricao().equals(m.getDescricao())
                        && s.valor().compareTo(m.getValor()) == 0 && s.tipo().equals(m.getTipo()) && s.conta().equals(m.getConta())))
                .forEach(m -> lancamentos.put(m.getId(), m));
        movimentacaoRepository.findByIdExternoIsNullAndConta(CONTA_LEGADA_TESTE).forEach(m -> lancamentos.put(m.getId(), m));

        if (confirmar) {
            transacao.executeWithoutResult(status -> {
                contaRepository.deleteAll(contasTeste);
                investimentoRepository.deleteAll(investimentosTeste);
                movimentacaoRepository.deleteAll(lancamentos.values());
            });
        }
        List<Map<String, Object>> amostra = lancamentos.values().stream()
                .sorted(Comparator.comparing(Movimentacao::getData).reversed())
                .limit(30)
                .map(m -> Map.<String, Object>of("data", m.getData().toString(), "descricao", m.getDescricao(),
                        "valor", m.getValor(), "conta", m.getConta() == null ? "" : m.getConta()))
                .toList();
        return Map.of(
                "removido", confirmar,
                "contas", contasTeste.size(),
                "nomesContas", contasTeste.stream().map(c -> c.getInstituicao() + " · " + c.getNome()).toList(),
                "investimentos", investimentosTeste.size(),
                "lancamentos", lancamentos.size(),
                "amostraLancamentos", amostra);
    }
}
