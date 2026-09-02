package br.com.controle.despesas;

import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ia")
@CrossOrigin(origins = "*")
public class AnaliseController {
    private final MovimentacaoRepository repository;

    public AnaliseController(MovimentacaoRepository repository) { this.repository = repository; }

    @PostMapping("/analisar")
    public Map<String, Object> analisar(@RequestBody(required = false) Map<String, String> mensagem) {
        BigDecimal saidas = repository.findAll().stream()
                .filter(item -> "SAIDA".equalsIgnoreCase(item.getTipo()))
                .map(Movimentacao::getValor).reduce(BigDecimal.ZERO, BigDecimal::add);
        String texto = mensagem == null ? "" : mensagem.getOrDefault("mensagem", "");
        return Map.of(
                "resposta", texto.isBlank()
                        ? "Posso analisar seus gastos, encontrar excessos e sugerir um limite seguro para cada categoria."
                        : "Registrei sua mensagem. Com os dados atuais, suas saídas acumuladas somam R$ " + saidas + ".",
                "totalSaidas", saidas,
                "sugestoes", List.of("Defina limites por categoria", "Reserve uma margem para imprevistos", "Revise despesas recorrentes"),
                "modo", "regras-locais"
        );
    }
}
