package br.com.controle.despesas.openfinance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ContaConectadaRepository extends JpaRepository<ContaConectada, UUID> {
    List<ContaConectada> findAllByOrderByInstituicaoAscNomeAsc();
    List<ContaConectada> findByItemId(String itemId);
    void deleteByItemId(String itemId);
}

