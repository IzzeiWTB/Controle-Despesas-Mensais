package br.com.controle.despesas;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/metas")
@CrossOrigin(origins = "*")
public class MetaFinanceiraController {
    private final MetaFinanceiraRepository repository;

    public MetaFinanceiraController(MetaFinanceiraRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<MetaFinanceira> listar() {
        return repository.findAllByOrderByNomeAsc();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MetaFinanceira criar(@Valid @RequestBody MetaFinanceira meta) {
        if (meta.getNome() != null) meta.setNome(meta.getNome().trim());
        if (meta.getValorAtual() == null) meta.setValorAtual(java.math.BigDecimal.ZERO);
        return repository.save(meta);
    }

    @PutMapping("/{id}")
    public MetaFinanceira atualizar(@PathVariable UUID id, @Valid @RequestBody MetaFinanceira dados) {
        MetaFinanceira meta = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meta nao encontrada"));
        meta.setNome(dados.getNome() != null ? dados.getNome().trim() : "");
        meta.setValorAlvo(dados.getValorAlvo());
        meta.setValorAtual(dados.getValorAtual() != null ? dados.getValorAtual() : java.math.BigDecimal.ZERO);
        meta.setPrazo(dados.getPrazo());
        return repository.save(meta);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void excluir(@PathVariable UUID id) {
        MetaFinanceira meta = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Meta nao encontrada"));
        repository.delete(meta);
    }
}
