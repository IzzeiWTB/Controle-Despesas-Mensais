package br.com.controle.despesas;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
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
        LocalDate inicioPeriodo = inicio != null ? inicio : hoje.withDayOfMonth(1);
        LocalDate fimPeriodo = fim != null ? fim : hoje.withDayOfMonth(hoje.lengthOfMonth());
        if (fimPeriodo.isBefore(inicioPeriodo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Periodo invalido");
        }
        return repository.findByDataBetweenOrderByDataDesc(
                inicioPeriodo, fimPeriodo);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Movimentacao criar(@Valid @RequestBody Movimentacao movimentacao) {
        return repository.save(movimentacao);
    }

    @PutMapping("/{id}")
    public Movimentacao atualizar(@PathVariable UUID id, @Valid @RequestBody Movimentacao dados) {
        Movimentacao movimentacao = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lancamento nao encontrado"));
        movimentacao.setDescricao(dados.getDescricao());
        movimentacao.setValor(dados.getValor());
        movimentacao.setData(dados.getData());
        movimentacao.setCategoria(dados.getCategoria());
        movimentacao.setTipo(dados.getTipo());
        movimentacao.setConta(dados.getConta());
        movimentacao.setRecorrente(dados.isRecorrente());
        return repository.save(movimentacao);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable UUID id) {
        Movimentacao movimentacao = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lancamento nao encontrado"));
        repository.delete(movimentacao);
    }
}
