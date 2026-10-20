package br.com.controle.despesas;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface MetaFinanceiraRepository extends JpaRepository<MetaFinanceira, UUID> {
    List<MetaFinanceira> findAllByOrderByNomeAsc();
}
