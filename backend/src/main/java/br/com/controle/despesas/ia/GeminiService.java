package br.com.controle.despesas.ia;

import br.com.controle.despesas.LimiteCategoria;
import br.com.controle.despesas.MetaFinanceira;
import br.com.controle.despesas.Movimentacao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.util.*;

@Service
public class GeminiService {
    private static final Logger log = LoggerFactory.getLogger(GeminiService.class);
    private static final List<String> CATEGORIAS_VALIDAS = List.of(
            "Alimentação", "Transporte", "Moradia", "Saúde", "Lazer", "Assinaturas", "Salário", "Outros"
    );

    @Value("${gemini.api.key:${GEMINI_API_KEY:}}")
    private String geminiApiKey;

    @Value("${gemini.model:gemini-2.5-flash}")
    private String geminiModel;

    private final ObjectMapper json;
    private final HttpClient httpClient;

    public GeminiService(ObjectMapper json) {
        this.json = json;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Map<String, Object> categorizar(String descricao) {
        if (descricao == null || descricao.isBlank()) {
            return Map.of("categoria", "Outros", "confianca", "BAIXA", "origem", "REGRA");
        }

        // Se houver chave do Gemini configurada, tenta IA primeiro
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                Map<String, Object> resultadoIa = categorizarComGemini(descricao);
                if (resultadoIa != null) {
                    return resultadoIa;
                }
            } catch (Exception e) {
                log.warn("Falha ao categorizar com Gemini, usando heuristica local: {}", e.getMessage());
            }
        }

        // Fallback heurístico inteligente
        String catHeuristica = categorizarHeuristica(descricao);
        return Map.of("categoria", catHeuristica, "confianca", catHeuristica.equals("Outros") ? "BAIXA" : "MEDIA", "origem", "HEURISTICA");
    }

    private Map<String, Object> categorizarComGemini(String descricao) throws Exception {
        String prompt = "Você é o categorizador financeiro do app Clareza. Analise a seguinte descrição de transação bancária/fatura: \""
                + descricao.replace("\"", "\\\"") + "\".\n"
                + "Classifique-a EXCLUSIVAMENTE em uma destas categorias: Alimentação, Transporte, Moradia, Saúde, Lazer, Assinaturas, Salário, Outros.\n"
                + "Responda APENAS com um objeto JSON no formato: {\"categoria\": \"...\", \"confianca\": \"ALTA|MEDIA|BAIXA\"}";

        Map<String, Object> payload = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(Map.of("text", prompt)))
                ),
                "generationConfig", Map.of(
                        "temperature", 0.1,
                        "responseMimeType", "application/json"
                )
        );

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonNode root = json.readTree(response.body());
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
            if (text != null && !text.isBlank()) {
                JsonNode parsed = json.readTree(text);
                String cat = parsed.path("categoria").asText();
                String conf = parsed.path("confianca").asText("ALTA");
                if (CATEGORIAS_VALIDAS.contains(cat)) {
                    return Map.of("categoria", cat, "confianca", conf, "origem", "GEMINI");
                }
            }
        }
        return null;
    }

    public Map<String, Object> gerarInsight(List<Movimentacao> movimentacoes,
                                            Map<String, BigDecimal> limites,
                                            List<MetaFinanceira> metas,
                                            String periodo) {
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            try {
                Map<String, Object> resultadoIa = gerarInsightComGemini(movimentacoes, limites, metas, periodo);
                if (resultadoIa != null) {
                    return resultadoIa;
                }
            } catch (Exception e) {
                log.warn("Falha ao gerar insight com Gemini, gerando insight estruturado local: {}", e.getMessage());
            }
        }

        return gerarInsightEstruturado(movimentacoes, limites, metas, periodo);
    }

    private Map<String, Object> gerarInsightComGemini(List<Movimentacao> movimentacoes,
                                                      Map<String, BigDecimal> limites,
                                                      List<MetaFinanceira> metas,
                                                      String periodo) throws Exception {
        BigDecimal totalEntradas = BigDecimal.ZERO;
        BigDecimal totalDespesas = BigDecimal.ZERO;
        Map<String, BigDecimal> despesasPorCategoria = new LinkedHashMap<>();

        for (Movimentacao m : movimentacoes) {
            if ("ENTRADA".equalsIgnoreCase(m.getTipo())) {
                totalEntradas = totalEntradas.add(m.getValor());
            } else {
                totalDespesas = totalDespesas.add(m.getValor());
                despesasPorCategoria.merge(m.getCategoria(), m.getValor(), BigDecimal::add);
            }
        }

        StringBuilder dados = new StringBuilder();
        dados.append("Período: ").append(periodo).append("\n");
        dados.append("Total de Entradas: R$ ").append(totalEntradas).append("\n");
        dados.append("Total de Despesas: R$ ").append(totalDespesas).append("\n");
        dados.append("Despesas por categoria: ").append(despesasPorCategoria).append("\n");
        dados.append("Limites de gastos estabelecidos: ").append(limites).append("\n");
        dados.append("Metas financeiras: ").append(metas.stream().map(m -> m.getNome() + ": " + m.getValorAtual() + "/" + m.getValorAlvo()).toList()).append("\n");

        String prompt = "Você é a inteligência do aplicativo Clareza. Seu tom é calmo, acolhedor, gentil e focado em dar clareza mental sobre o dinheiro.\n"
                + "Analise estes dados do mês e gere um resumo financeiro acolhedor.\n"
                + "Dados:\n" + dados + "\n"
                + "Responda ESTRITAMENTE em formato JSON com a seguinte estrutura:\n"
                + "{\n"
                + "  \"titulo\": \"Uma frase curta e acolhedora\",\n"
                + "  \"resumo\": \"Um parágrafo de 2 a 3 frases com leitura humanizada do mês\",\n"
                + "  \"destaque\": \"O ponto mais relevante de atenção ou comemoração\",\n"
                + "  \"sugestoes\": [\"sugestao 1\", \"sugestao 2\"]\n"
                + "}";

        Map<String, Object> payload = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("temperature", 0.3, "responseMimeType", "application/json")
        );

        String url = "https://generativelanguage.googleapis.com/v1beta/models/" + geminiModel + ":generateContent?key=" + geminiApiKey;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonNode root = json.readTree(response.body());
            String text = root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
            if (text != null && !text.isBlank()) {
                Map<String, Object> parsed = json.readValue(text, Map.class);
                parsed.put("origem", "GEMINI");
                return parsed;
            }
        }
        return null;
    }

    private Map<String, Object> gerarInsightEstruturado(List<Movimentacao> movimentacoes,
                                                        Map<String, BigDecimal> limites,
                                                        List<MetaFinanceira> metas,
                                                        String periodo) {
        if (movimentacoes.isEmpty()) {
            return Map.of(
                    "titulo", "Seu resumo está pronto.",
                    "resumo", "Inclua seus lançamentos para receber uma leitura personalizada do mês.",
                    "destaque", "Aguardando seus primeiros registros.",
                    "sugestoes", List.of("Comece registrando seus gastos fixos e recorrentes."),
                    "origem", "REGRAS"
            );
        }

        BigDecimal entradas = BigDecimal.ZERO;
        BigDecimal despesas = BigDecimal.ZERO;
        Map<String, BigDecimal> categorias = new HashMap<>();

        for (Movimentacao m : movimentacoes) {
            if ("ENTRADA".equalsIgnoreCase(m.getTipo())) {
                entradas = entradas.add(m.getValor());
            } else {
                despesas = despesas.add(m.getValor());
                categorias.merge(m.getCategoria(), m.getValor(), BigDecimal::add);
            }
        }

        String maiorCategoria = "Outros";
        BigDecimal maiorValor = BigDecimal.ZERO;
        for (var entry : categorias.entrySet()) {
            if (entry.getValue().compareTo(maiorValor) > 0) {
                maiorValor = entry.getValue();
                maiorCategoria = entry.getKey();
            }
        }

        BigDecimal saldo = entradas.subtract(despesas);
        boolean positivo = saldo.compareTo(BigDecimal.ZERO) >= 0;

        String titulo = positivo ? "Seu mês está em equilíbrio." : "Atenção ao fluxo do mês.";
        String resumo = positivo
                ? String.format("Suas entradas superam os gastos em R$ %.2f. A maior parte das suas despesas foi com %s.", saldo.doubleValue(), maiorCategoria)
                : String.format("As despesas superaram as entradas em R$ %.2f. A categoria com maior impacto foi %s.", saldo.abs().doubleValue(), maiorCategoria);

        String destaque = String.format("A maior despesa concentrada está em %s (R$ %.2f).", maiorCategoria, maiorValor.doubleValue());

        List<String> sugestoes = new ArrayList<>();
        if (limites != null && limites.containsKey(maiorCategoria)) {
            BigDecimal lim = limites.get(maiorCategoria);
            if (maiorValor.compareTo(lim) > 0) {
                sugestoes.add(String.format("Você ultrapassou o teto planejado para %s em R$ %.2f.", maiorCategoria, maiorValor.subtract(lim).doubleValue()));
            }
        }
        if (!metas.isEmpty()) {
            MetaFinanceira m = metas.get(0);
            sugestoes.add(String.format("Meta \"%s\": você já conquistou %s do seu objetivo.", m.getNome(),
                    m.getValorAlvo().compareTo(BigDecimal.ZERO) > 0
                            ? Math.round(m.getValorAtual().doubleValue() / m.getValorAlvo().doubleValue() * 100) + "%"
                            : "0%"));
        } else {
            sugestoes.add("Que tal definir uma meta de economia para direcionar o saldo restante?");
        }

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("titulo", titulo);
        resultado.put("resumo", resumo);
        resultado.put("destaque", destaque);
        resultado.put("sugestoes", sugestoes);
        resultado.put("origem", "LOCAL");
        return resultado;
    }

    public static String categorizarHeuristica(String raw) {
        if (raw == null) return "Outros";
        String s = Normalizer.normalize(raw.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        if (s.matches(".*\\b(mercado|supermercado|ifood|restaurante|alelo|sodexo|padaria|lanchonete|burger|pizza|hamburguer|cafe|acougue|hortifruti|comida|mcdonalds|subway|bar|churrascaria|marmita|lanche)\\b.*")) {
            return "Alimentação";
        }
        if (s.matches(".*\\b(uber|99|99pop|taxi|combustivel|posto|ipiranga|shell|gasolina|etanol|pedagio|estacionamento|metro|onibus|passagem|bilhete|sem parar|conectcar|veloe)\\b.*")) {
            return "Transporte";
        }
        if (s.matches(".*\\b(aluguel|condominio|luz|agua|enel|sabesp|cpfl|gas|comgas|iptu|reforma|energia|imobiliaria|quintoandar|loft)\\b.*")) {
            return "Moradia";
        }
        if (s.matches(".*\\b(farmacia|drogaria|raia|drogasil|pacheco|sao paulo|panvel|medico|consulta|exame|dentista|hospital|remedio|psicologo|laboratorio|unimed|bradesco saude|sulamerica|plano de saude)\\b.*")) {
            return "Saúde";
        }
        if (s.matches(".*\\b(cinema|show|teatro|viagem|hotel|airbnb|festa|ingresso|jogo|steam|playstation|xbox|nintendo|praia|parque|passeio)\\b.*")) {
            return "Lazer";
        }
        if (s.matches(".*\\b(netflix|spotify|amazon|prime|youtube|hbo|max|disney|globo|deezer|apple|icloud|google one|chatgpt|openai|cursor|github)\\b.*")) {
            return "Assinaturas";
        }
        if (s.matches(".*\\b(salario|adiantamento|holerite|folha|remuneracao|pro-labore|honorarios|empresa pagamento)\\b.*")) {
            return "Salário";
        }
        return "Outros";
    }

    public boolean isConfigurado() {
        return geminiApiKey != null && !geminiApiKey.isBlank();
    }
}

