package br.com.controle.despesas;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/limites")
@CrossOrigin(origins = "*")
public class LimiteCategoriaController {
    private final LimiteCategoriaRepository repository;

    public LimiteCategoriaController(LimiteCategoriaRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public Map<String, BigDecimal> listar() {
        Map<String, BigDecimal> resultado = new LinkedHashMap<>();
        for (LimiteCategoria item : repository.findAll()) {
            resultado.put(item.getCategoria(), item.getLimite());
        }
        return resultado;
    }

    @PutMapping
    @Transactional
    public Map<String, BigDecimal> atualizar(@RequestBody Map<String, BigDecimal> limites) {
        if (limites == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limites invalidos");
        }
        for (Map.Entry<String, BigDecimal> entry : limites.entrySet()) {
            String categoria = entry.getKey() == null ? "" : entry.getKey().trim();
            BigDecimal valor = entry.getValue() == null ? BigDecimal.ZERO : entry.getValue();
            if (categoria.isEmpty() || categoria.length() > 60
                    || valor.compareTo(BigDecimal.ZERO) < 0
                    || valor.scale() > 2
                    || valor.precision() - valor.scale() > 12) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valor de limite invalido para " + categoria);
            }
            if (valor.compareTo(BigDecimal.ZERO) == 0) {
                if (repository.existsById(categoria)) {
                    repository.deleteById(categoria);
                }
            } else {
                repository.save(new LimiteCategoria(categoria, valor));
            }
        }
        return listar();
    }
}
