package br.com.controle.despesas.resumo;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Saldo total, faturas, investimentos, gastos previstos e projecao do mes. */
@RestController
@RequestMapping("/api/resumo")
public class ResumoController {
    private final ResumoService service;
    private final Clock clock;

    public ResumoController(ResumoService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @GetMapping
    public Map<String, Object> resumo(@RequestParam(required = false) String periodo) {
        YearMonth mes;
        try {
            mes = periodo == null || periodo.isBlank() ? YearMonth.now(clock) : YearMonth.parse(periodo);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Período inválido; use AAAA-MM.");
        }
        return service.resumo(mes);
    }
}
