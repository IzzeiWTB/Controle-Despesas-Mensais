package br.com.controle.despesas;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "meta_financeira")
public class MetaFinanceira {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Size(max = 80)
    private String nome;

    @NotNull
    @DecimalMin(value = "0.01")
    @Digits(integer = 12, fraction = 2)
    private BigDecimal valorAlvo;

    @NotNull
    @DecimalMin(value = "0.00")
    @Digits(integer = 12, fraction = 2)
    private BigDecimal valorAtual;

    private LocalDate prazo;

    @PrePersist
    void preencherPadroes() {
        if (valorAtual == null) valorAtual = BigDecimal.ZERO;
    }

    public UUID getId() { return id; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public BigDecimal getValorAlvo() { return valorAlvo; }
    public void setValorAlvo(BigDecimal valorAlvo) { this.valorAlvo = valorAlvo; }
    public BigDecimal getValorAtual() { return valorAtual; }
    public void setValorAtual(BigDecimal valorAtual) { this.valorAtual = valorAtual; }
    public LocalDate getPrazo() { return prazo; }
    public void setPrazo(LocalDate prazo) { this.prazo = prazo; }
}
