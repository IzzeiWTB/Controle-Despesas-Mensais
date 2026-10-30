package br.com.controle.despesas;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RelogioConfig {
    public static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");

    @Bean
    public Clock clock() {
        return Clock.system(FUSO);
    }
}
