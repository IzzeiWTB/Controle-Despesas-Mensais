package br.com.controle.despesas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "APP_API_KEY=integration-test-only",
        "spring.datasource.url=jdbc:h2:mem:clareza-flyway;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "gemini.api.key=",
        "pluggy.client.id=",
        "pluggy.client.secret="
})
class MigracaoFlywayTest {
    static final String VERSAO_ATUAL = "5";

    @Autowired Flyway flyway;

    @Test void aplicaTodasAsMigracoesSemPendencias() {
        assertEquals(VERSAO_ATUAL, flyway.info().current().getVersion().getVersion());
        assertEquals(0, flyway.info().pending().length);
        assertTrue(flyway.info().applied().length >= 2);
    }
}
