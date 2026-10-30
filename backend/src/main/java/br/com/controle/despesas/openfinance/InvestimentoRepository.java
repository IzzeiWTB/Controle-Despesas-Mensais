package br.com.controle.despesas.openfinance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvestimentoRepository extends JpaRepository<Investimento, UUID> {
    List<Investimento> findAllByOrderByInstituicaoAscNomeAsc();
    List<Investimento> findByItemId(String itemId);
    Optional<Investimento> findByItemIdAndIdExterno(String itemId, String idExterno);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from Investimento i where i.itemId = :itemId")
    void apagarPorItemId(@Param("itemId") String itemId);
}
