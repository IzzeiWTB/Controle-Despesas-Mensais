package br.com.controle.despesas;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface MovimentacaoRepository extends JpaRepository<Movimentacao, UUID> {
    List<Movimentacao> findByDataBetweenOrderByDataDesc(LocalDate inicio, LocalDate fim);
}
