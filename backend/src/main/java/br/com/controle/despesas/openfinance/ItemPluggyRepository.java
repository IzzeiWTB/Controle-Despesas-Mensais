package br.com.controle.despesas.openfinance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItemPluggyRepository extends JpaRepository<ItemPluggy, String> {
    List<ItemPluggy> findAllByOrderByCriadoEmAsc();
}
