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

    @PostMapping("/recorrencias")
    @org.springframework.transaction.annotation.Transactional
    public java.util.Map<String, Object> repetirRecorrentes(@RequestParam String periodo) {
        java.time.YearMonth destino;
        try {
            destino = java.time.YearMonth.parse(periodo);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Periodo invalido");
        }
        java.time.YearMonth origem = destino.minusMonths(1);
        List<Movimentacao> recorrentesOrigem = repository.findByRecorrenteTrueAndDataBetweenOrderByDataAsc(
                origem.atDay(1), origem.atEndOfMonth());
        List<Movimentacao> existentesDestino = repository.findByDataBetweenOrderByDataDesc(
                destino.atDay(1), destino.atEndOfMonth());
        List<Movimentacao> criados = new java.util.ArrayList<>();
        for (Movimentacao item : recorrentesOrigem) {
            boolean jaExiste = java.util.stream.Stream.concat(existentesDestino.stream(), criados.stream()).anyMatch(existente ->
                    existente.isRecorrente()
                            && existente.getDescricao().equalsIgnoreCase(item.getDescricao())
                            && existente.getTipo().equalsIgnoreCase(item.getTipo())
                            && existente.getCategoria().equalsIgnoreCase(item.getCategoria())
                            && existente.getValor().compareTo(item.getValor()) == 0);
            if (!jaExiste) {
                Movimentacao novo = new Movimentacao();
                novo.setDescricao(item.getDescricao());
                novo.setValor(item.getValor());
                int dia = Math.min(item.getData().getDayOfMonth(), destino.lengthOfMonth());
                novo.setData(destino.atDay(dia));
                novo.setCategoria(item.getCategoria());
                novo.setTipo(item.getTipo());
                novo.setConta(item.getConta());
                novo.setRecorrente(true);
                criados.add(repository.save(novo));
            }
        }
        return java.util.Map.of("criados", criados.size(), "itens", criados);
    }
}
