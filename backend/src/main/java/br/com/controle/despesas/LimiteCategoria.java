package br.com.controle.despesas;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

@Entity
@Table(name = "limite_categoria")
public class LimiteCategoria {
    @Id
    @NotBlank
    @Size(max = 60)
    @Column(length = 60)
    private String categoria;

    @NotNull
    @DecimalMin(value = "0.00")
    @Digits(integer = 12, fraction = 2)
    private BigDecimal limite;

    public LimiteCategoria() {}

    public LimiteCategoria(String categoria, BigDecimal limite) {
        this.categoria = categoria;
        this.limite = limite;
    }

    public String getCategoria() { return categoria; }
    public void setCategoria(String categoria) { this.categoria = categoria; }
    public BigDecimal getLimite() { return limite; }
    public void setLimite(BigDecimal limite) { this.limite = limite; }
}
