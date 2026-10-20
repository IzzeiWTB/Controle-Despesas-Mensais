package br.com.controle.despesas.openfinance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface InvestimentoRepository extends JpaRepository<Investimento, UUID> {
    List<Investimento> findAllByOrderByInstituicaoAscNomeAsc();
    List<Investimento> findByItemId(String itemId);
    void deleteByItemId(String itemId);
}

