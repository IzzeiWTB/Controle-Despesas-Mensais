package br.com.controle.despesas.openfinance;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "investimento")
public class Investimento {

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
    @Size(max = 120)
    private String nome;

    @NotBlank
    @Size(max = 40)
    private String tipo; // RENDA_FIXA, ACOES, FUNDOS, CRIPTO, OUTROS

    @NotNull
    @DecimalMin("0.00")
    @Column(precision = 38, scale = 2)
    private BigDecimal valorInvestido;

    @Column(precision = 38, scale = 2)
    private BigDecimal rentabilidade;

    @NotNull
    private LocalDateTime atualizadoEm;

    @Column(name = "id_externo", length = 100)
    private String idExterno;

    @PrePersist
    void aoCriar() {
        if (atualizadoEm == null) atualizadoEm = LocalDateTime.now();
    }

    @PreUpdate
    void aoAtualizar() {
        atualizadoEm = LocalDateTime.now();
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
    public BigDecimal getValorInvestido() { return valorInvestido; }
    public void setValorInvestido(BigDecimal valorInvestido) { this.valorInvestido = valorInvestido; }
    public BigDecimal getRentabilidade() { return rentabilidade; }
    public void setRentabilidade(BigDecimal rentabilidade) { this.rentabilidade = rentabilidade; }
    public String getIdExterno() { return idExterno; }
    public void setIdExterno(String idExterno) { this.idExterno = idExterno; }
    public LocalDateTime getAtualizadoEm() { return atualizadoEm; }
    public void setAtualizadoEm(LocalDateTime atualizadoEm) { this.atualizadoEm = atualizadoEm; }
}

