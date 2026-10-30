package br.com.controle.despesas;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** Com o relogio fixo em 2026-10-31T23:30-03:00 (ja e 01/11 em UTC), o periodo padrao deve ser outubro. */
@SpringBootTest(properties = {
        "APP_API_KEY=integration-test-only",
        "spring.datasource.url=jdbc:h2:mem:clareza-relogio;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "gemini.api.key=",
        "pluggy.client.id=",
        "pluggy.client.secret="
})
@AutoConfigureMockMvc
@Transactional
class RelogioTest {
    private static final String KEY = "integration-test-only";

    @TestConfiguration
    static class RelogioFixo {
        @Bean
        @Primary
        Clock clockFixo() {
            return Clock.fixed(Instant.parse("2026-11-01T02:30:00Z"), ZoneId.of("America/Sao_Paulo"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;

    private void criar(String descricao, String data) throws Exception {
        mvc.perform(post("/api/movimentacoes").header("X-API-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("descricao", descricao, "valor", "10.00", "data", data,
                                "categoria", "Outros", "tipo", "SAIDA", "conta", "Carteira", "recorrente", false))))
                .andExpect(status().isCreated());
    }

    @Test void relogioFixoEstaAtivoNoFusoDeSaoPaulo() {
        org.junit.jupiter.api.Assertions.assertEquals(java.time.LocalDate.parse("2026-10-31"), java.time.LocalDate.now(clock));
    }

    @Test void listagemPadraoUsaOMesDoFusoBrasileiro() throws Exception {
        criar("Fim de outubro", "2026-10-31");
        criar("Inicio de novembro", "2026-11-01");
        mvc.perform(get("/api/movimentacoes").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].descricao").value("Fim de outubro"));
    }

    @Test void insightsSemPeriodoUsamOutubro() throws Exception {
        criar("Fim de outubro", "2026-10-31");
        mvc.perform(get("/api/ia/insights").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destaque", not(is("Aguardando seus primeiros registros."))));
    }
}
