package br.com.controle.despesas.openfinance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContaConectadaRepository extends JpaRepository<ContaConectada, UUID> {
    List<ContaConectada> findAllByOrderByInstituicaoAscNomeAsc();
    List<ContaConectada> findByItemId(String itemId);
    Optional<ContaConectada> findByItemIdAndIdExterno(String itemId, String idExterno);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ContaConectada c where c.itemId = :itemId")
    void apagarPorItemId(@Param("itemId") String itemId);
}
