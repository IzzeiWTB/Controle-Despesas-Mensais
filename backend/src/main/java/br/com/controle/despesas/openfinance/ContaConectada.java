package br.com.controle.despesas.openfinance;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "conta_conectada")
public class ContaConectada {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Size(max = 100)
    private String itemId;

    @NotBlank
    @Size(max = 80)
    private String instituicao;

    @NotBlank
    @Size(max = 100)
    private String nome;

    @NotBlank
    @Size(max = 30)
    private String tipo; // CORRENTE, CARTAO_CREDITO, POUPANCA, INVESTIMENTO

    private BigDecimal saldo;
    private BigDecimal limiteCredito;
    private BigDecimal faturaAtual;

    @NotNull
    private LocalDateTime atualizadoEm;

    @PrePersist
    @PreUpdate
    void antesDeSalvar() {
        if (atualizadoEm == null) atualizadoEm = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public String getItemId() { return itemId; }
    public void setItemId(String itemId) { this.itemId = itemId; }
    public String getInstituicao() { return instituicao; }
    public void setInstituicao(String instituicao) { this.instituicao = instituicao; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public BigDecimal getSaldo() { return saldo; }
    public void setSaldo(BigDecimal saldo) { this.saldo = saldo; }
    public BigDecimal getLimiteCredito() { return limiteCredito; }
    public void setLimiteCredito(BigDecimal limiteCredito) { this.limiteCredito = limiteCredito; }
    public BigDecimal getFaturaAtual() { return faturaAtual; }
    public void setFaturaAtual(BigDecimal faturaAtual) { this.faturaAtual = faturaAtual; }
    public LocalDateTime getAtualizadoEm() { return atualizadoEm; }
    public void setAtualizadoEm(LocalDateTime atualizadoEm) { this.atualizadoEm = atualizadoEm; }
}

