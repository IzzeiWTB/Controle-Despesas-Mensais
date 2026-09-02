package br.com.controle.despesas;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/movimentacoes")
@CrossOrigin(origins = "*")
public class MovimentacaoController {
    private final MovimentacaoRepository repository;

    public MovimentacaoController(MovimentacaoRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Movimentacao> listar(@RequestParam(required = false) LocalDate inicio,
                                     @RequestParam(required = false) LocalDate fim) {
        LocalDate hoje = LocalDate.now();
        return repository.findByDataBetweenOrderByDataDesc(
                inicio != null ? inicio : hoje.withDayOfMonth(1),
                fim != null ? fim : hoje.withDayOfMonth(hoje.lengthOfMonth()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Movimentacao criar(@Valid @RequestBody Movimentacao movimentacao) {
        return repository.save(movimentacao);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable UUID id) { repository.deleteById(id); }
}
