package br.com.controle.despesas.ia;

import br.com.controle.despesas.LimiteCategoria;
import br.com.controle.despesas.LimiteCategoriaRepository;
import br.com.controle.despesas.MetaFinanceiraRepository;
import br.com.controle.despesas.Movimentacao;
import br.com.controle.despesas.MovimentacaoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ia")
@CrossOrigin(origins = "*")
public class IaController {

    private final GeminiService geminiService;
    private final MovimentacaoRepository movimentacaoRepository;
    private final LimiteCategoriaRepository limiteRepository;
    private final MetaFinanceiraRepository metaRepository;

    public IaController(GeminiService geminiService,
                        MovimentacaoRepository movimentacaoRepository,
                        LimiteCategoriaRepository limiteRepository,
                        MetaFinanceiraRepository metaRepository) {
        this.geminiService = geminiService;
        this.movimentacaoRepository = movimentacaoRepository;
        this.limiteRepository = limiteRepository;
        this.metaRepository = metaRepository;
    }

    @PostMapping("/categorizar")
    public Map<String, Object> categorizar(@RequestBody(required = false) Map<String, String> body) {
        String descricao = body != null ? body.get("descricao") : null;
        if (descricao == null || descricao.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Descricao obrigatoria");
        }
        return geminiService.categorizar(descricao);
    }

    @GetMapping("/insights")
    public Map<String, Object> obterInsights(@RequestParam(required = false) String periodo) {
        YearMonth ym;
        try {
            ym = (periodo != null && !periodo.isBlank()) ? YearMonth.parse(periodo) : YearMonth.now();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Periodo invalido. Formato esperado: YYYY-MM");
        }

        LocalDate inicio = ym.atDay(1);
        LocalDate fim = ym.atEndOfMonth();

        List<Movimentacao> movimentacoes = movimentacaoRepository.findByDataBetweenOrderByDataDesc(inicio, fim);

        Map<String, BigDecimal> limites = new LinkedHashMap<>();
        for (LimiteCategoria lc : limiteRepository.findAll()) {
            limites.put(lc.getCategoria(), lc.getLimite());
        }

        var metas = metaRepository.findAllByOrderByNomeAsc();

        return geminiService.gerarInsight(movimentacoes, limites, metas, ym.toString());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "iaAtiva", geminiService.isConfigurado(),
                "provedor", "Google Gemini",
                "modelo", "gemini-2.5-flash"
        );
    }
}

