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
}
