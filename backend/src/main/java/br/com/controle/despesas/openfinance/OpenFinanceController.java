package br.com.controle.despesas.openfinance;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
        if (itemId == null || itemId.isBlank() || itemId.trim().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "itemId obrigatorio (ate 100 caracteres)");
        }
        return pluggyService.sincronizarRegistrandoErro(itemId.trim());
    }

    /** Conexoes vinculadas (ex.: bancos do Meu Pluggy), com a ultima sincronizacao e o ultimo erro. */
    @GetMapping("/itens")
    public List<ItemPluggy> listarItens() {
        return pluggyService.listarItens();
    }

    /** Vincula um Item ID copiado do painel da Pluggy e sincroniza. Corpo: {"itemId": "...", "nome": "Nubank"}. */
    @PostMapping("/itens")
    public Map<String, Object> vincularItem(@RequestBody(required = false) Map<String, String> body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o itemId");
        return pluggyService.vincularItem(body.get("itemId"), body.get("nome"));
    }

    /** Sincroniza todos os itens vinculados; com apenasDesatualizados=true pula os sincronizados nas ultimas 6 horas. */
    @PostMapping("/sincronizar-todos")
    public Map<String, Object> sincronizarTodos(@RequestParam(defaultValue = "false") boolean apenasDesatualizados) {
        return pluggyService.sincronizarTodos(apenasDesatualizados);
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

    @ExceptionHandler(PluggyIndisponivelException.class)
    ResponseEntity<Map<String, String>> pluggyIndisponivel(PluggyIndisponivelException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("erro", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> requisicaoInvalida(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("erro", e.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> conflito() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("erro", "Sincronização concorrente; tente novamente."));
    }
}
