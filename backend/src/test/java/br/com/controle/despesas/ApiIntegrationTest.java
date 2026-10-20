package br.com.controle.despesas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "APP_API_KEY=integration-test-only",
        "spring.datasource.url=jdbc:h2:mem:clareza-test;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@Transactional
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private static final String KEY = "integration-test-only";

    private String payload(String type, String amount, String date) throws Exception {
        return json.writeValueAsString(Map.of("descricao", "Mercado de teste", "valor", amount,
                "data", date, "categoria", "Alimentação", "tipo", type, "conta", "Carteira", "recorrente", true));
    }

    @Test void requiresKeyAndDoesNotCacheFinancialData() throws Exception {
        mvc.perform(get("/api/movimentacoes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/movimentacoes").header("X-API-Key", "wrong"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/movimentacoes").header("X-API-Key", KEY))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @Test void allowsBrowserPreflightWithoutExposingData() throws Exception {
        mvc.perform(options("/api/movimentacoes").header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-api-key"))
                .andExpect(status().isOk()).andExpect(header().exists("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/movimentacoes").header("Origin", "http://localhost:3000"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "*"));
        mvc.perform(options("/api/movimentacoes").header("Origin", "https://localhost")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-api-key"))
                .andExpect(status().isOk()).andExpect(header().exists("Access-Control-Allow-Origin"));
    }

    @Test void createsReadsUpdatesAndDeletesWithinPeriod() throws Exception {
        var result = mvc.perform(post("/api/movimentacoes").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(payload("SAIDA", "12.35", "2026-10-16")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.valor").value(12.35)).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/api/movimentacoes?inicio=2026-10-01&fim=2026-10-31").header("X-API-Key", KEY))
                .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].conta").value("Carteira"));
        mvc.perform(get("/api/movimentacoes?inicio=2026-11-01&fim=2026-11-30").header("X-API-Key", KEY))
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(put("/api/movimentacoes/" + id).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(payload("ENTRADA", "20.00", "2026-10-16")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tipo").value("ENTRADA"));
        mvc.perform(delete("/api/movimentacoes/" + id).header("X-API-Key", KEY)).andExpect(status().isNoContent());
        mvc.perform(get("/api/movimentacoes?inicio=2026-10-01&fim=2026-10-31").header("X-API-Key", KEY))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test void rejectsInvalidTypePrecisionAndDateRange() throws Exception {
        for (String[] invalid : new String[][]{{"INVALIDO", "10"}, {"SAIDA", "-1"}, {"SAIDA", "1.001"}}) {
            mvc.perform(post("/api/movimentacoes").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                            .content(payload(invalid[0], invalid[1], "2026-10-16")))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/movimentacoes?inicio=2026-11-01&fim=2026-10-01").header("X-API-Key", KEY))
                .andExpect(status().isBadRequest());
    }

    @Test void returnsNotFoundForUnknownRecords() throws Exception {
        String path = "/api/movimentacoes/" + UUID.randomUUID();
        mvc.perform(put(path).header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(payload("SAIDA", "10", "2026-10-16")))
                .andExpect(status().isNotFound());
        mvc.perform(delete(path).header("X-API-Key", KEY)).andExpect(status().isNotFound());
    }

    @Test void servesAppWithoutExposingKey() throws Exception {
        mvc.perform(get("/index.html")).andExpect(status().isOk())
                .andExpect(content().string(containsString("access-form")))
                .andExpect(content().string(not(containsString(KEY))));
    }

    @Test void managesCategoryLimitsWithValidation() throws Exception {
        mvc.perform(get("/api/limites").header("X-API-Key", KEY))
                .andExpect(status().isOk());

        String limitesPayload = json.writeValueAsString(Map.of("Alimentação", "250.00", "Transporte", "100.50"));
        mvc.perform(put("/api/limites").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(limitesPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Alimentação").value(250.00))
                .andExpect(jsonPath("$.Transporte").value(100.50));

        mvc.perform(get("/api/limites").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Alimentação").value(250.00));

        // Invalid: negative value
        mvc.perform(put("/api/limites").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("Alimentação", "-10"))))
                .andExpect(status().isBadRequest());

        // Zero deletes category
        mvc.perform(put("/api/limites").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("Alimentação", "0"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Alimentação").doesNotExist());
    }

    @Test void managesFinancialGoalsCrudAndValidation() throws Exception {
        String metaPayload = json.writeValueAsString(Map.of(
                "nome", "Reserva de emergência",
                "valorAlvo", "5000.00",
                "valorAtual", "1200.00",
                "prazo", "2026-12-31"
        ));

        var created = mvc.perform(post("/api/metas").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(metaPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.nome").value("Reserva de emergência"))
                .andExpect(jsonPath("$.valorAlvo").value(5000.00))
                .andExpect(jsonPath("$.valorAtual").value(1200.00))
                .andReturn();

        String metaId = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(get("/api/metas").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));

        String updatePayload = json.writeValueAsString(Map.of(
                "nome", "Reserva Atualizada",
                "valorAlvo", "6000.00",
                "valorAtual", "2000.00"
        ));
        mvc.perform(put("/api/metas/" + metaId).header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Reserva Atualizada"))
                .andExpect(jsonPath("$.valorAlvo").value(6000.00))
                .andExpect(jsonPath("$.valorAtual").value(2000.00));

        mvc.perform(delete("/api/metas/" + metaId).header("X-API-Key", KEY))
                .andExpect(status().isNoContent());

        // Invalid: empty nome
        mvc.perform(post("/api/metas").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("nome", "", "valorAlvo", "100.00"))))
                .andExpect(status().isBadRequest());
    }

    @Test void repeatsRecurringMovementsToTargetMonth() throws Exception {
        // Create recurrent item in 2026-09
        String item = json.writeValueAsString(Map.of(
                "descricao", "Assinatura Streaming",
                "valor", "45.90",
                "data", "2026-09-10",
                "categoria", "Assinaturas",
                "tipo", "SAIDA",
                "conta", "Nubank",
                "recorrente", true
        ));
        mvc.perform(post("/api/movimentacoes").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(item))
                .andExpect(status().isCreated());

        // Call repeat for 2026-10
        mvc.perform(post("/api/movimentacoes/recorrencias?periodo=2026-10").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criados").value(1));

        // Verify it was created in 2026-10
        mvc.perform(get("/api/movimentacoes?inicio=2026-10-01&fim=2026-10-31").header("X-API-Key", KEY))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].descricao").value("Assinatura Streaming"))
                .andExpect(jsonPath("$[0].data").value("2026-10-10"))
                .andExpect(jsonPath("$[0].recorrente").value(true));

        // Call again -> idempotent, 0 created
        mvc.perform(post("/api/movimentacoes/recorrencias?periodo=2026-10").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.criados").value(0));
    }

    @Test void categorizesTransactionsAndProvidesAiInsights() throws Exception {
        // AI Categorization
        mvc.perform(post("/api/ia/categorizar").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("descricao", "Almoço no Restaurante Fogão"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoria").value("Alimentação"));

        mvc.perform(post("/api/ia/categorizar").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("descricao", "Uber para o aeroporto"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoria").value("Transporte"));

        mvc.perform(post("/api/ia/categorizar").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("descricao", "Netflix mensalidade"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoria").value("Assinaturas"));

        mvc.perform(post("/api/ia/categorizar").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("descricao", ""))))
                .andExpect(status().isBadRequest());

        // AI Status
        mvc.perform(get("/api/ia/status").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provedor").value("Google Gemini"));

        // AI Insights
        mvc.perform(get("/api/ia/insights?periodo=2026-10").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").isNotEmpty())
                .andExpect(jsonPath("$.resumo").isNotEmpty())
                .andExpect(jsonPath("$.sugestoes").isArray());
    }

    @Test void managesOpenFinanceConnectionsAndInvestments() throws Exception {
        // Status and Connect Token
        mvc.perform(get("/api/open-finance/status").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provedor").value(containsString("Pluggy")));

        mvc.perform(post("/api/open-finance/connect-token").header("X-API-Key", KEY))
                .andExpect(status().isOk());

        // Sync Sandbox
        mvc.perform(post("/api/open-finance/sincronizar").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("itemId", "mock-sandbox-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sucesso").value(true))
                .andExpect(jsonPath("$.contasSincronizadas").value(3))
                .andExpect(jsonPath("$.investimentosSincronizados").value(3));

        // List connected accounts and credit cards
        mvc.perform(get("/api/open-finance/contas").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(3))))
                .andExpect(jsonPath("$[?(@.tipo == 'CARTAO_CREDITO')].faturaAtual").isNotEmpty());

        // List consolidated investments
        mvc.perform(get("/api/open-finance/investimentos").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(3))))
                .andExpect(jsonPath("$[0].valorInvestido").isNotEmpty());
    }
}
