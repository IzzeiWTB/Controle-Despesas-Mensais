package br.com.controle.despesas.openfinance;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/open-finance")
@CrossOrigin(origins = "*")
public class OpenFinanceController {

    private final PluggyService pluggyService;
    private final ContaConectadaRepository contaRepository;
    private final InvestimentoRepository investimentoRepository;

    public OpenFinanceController(PluggyService pluggyService,
                                 ContaConectadaRepository contaRepository,
                                 InvestimentoRepository investimentoRepository) {
        this.pluggyService = pluggyService;
        this.contaRepository = contaRepository;
        this.investimentoRepository = investimentoRepository;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "configurado", pluggyService.isConfigurado(),
                "provedor", "Pluggy Open Finance (Brasil)",
                "totalContasConectadas", contaRepository.count(),
                "totalInvestimentos", investimentoRepository.count()
        );
    }

    @PostMapping("/connect-token")
    public Map<String, Object> connectToken() {
        return pluggyService.gerarConnectToken();
    }

    @PostMapping("/sincronizar")
    public Map<String, Object> sincronizar(@RequestBody(required = false) Map<String, String> body) {
        String itemId = body != null ? body.get("itemId") : null;
        return pluggyService.sincronizarItem(itemId);
    }

    @GetMapping("/contas")
    public List<ContaConectada> listarContas() {
        return contaRepository.findAllByOrderByInstituicaoAscNomeAsc();
    }

    @GetMapping("/investimentos")
    public List<Investimento> listarInvestimentos() {
        return investimentoRepository.findAllByOrderByInstituicaoAscNomeAsc();
    }

    @DeleteMapping("/itens/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removerItem(@PathVariable String itemId) {
        pluggyService.removerItem(itemId);
    }
}

