package br.com.controle.despesas;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
public class Movimentacao {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Size(max = 100)
    private String descricao;

    @NotNull
    @DecimalMin(value = "0.01")
    @Digits(integer = 12, fraction = 2)
    @Column(precision = 38, scale = 2)
    private BigDecimal valor;

    @NotNull
    private LocalDate data;

    @NotBlank
    @Size(max = 60)
    private String categoria;

    @NotBlank
    @Pattern(regexp = "ENTRADA|SAIDA")
    private String tipo;

    @Size(max = 60)
    private String conta;
    private boolean recorrente;

    /** Identificador do lancamento no provedor externo (Pluggy). Somente leitura para o cliente. */
    @Column(name = "id_externo", length = 120, unique = true)
    private String idExterno;

    /** Lancamento recorrente que originou este clone. Somente leitura para o cliente. */
    @Column(name = "recorrencia_origem_id", unique = true)
    private UUID recorrenciaOrigemId;

    public UUID getId() { return id; }
    public String getDescricao() { return descricao; }
    public void setDescricao(String descricao) { this.descricao = descricao; }
    public BigDecimal getValor() { return valor; }
    public void setValor(BigDecimal valor) { this.valor = valor; }
    public LocalDate getData() { return data; }
    public void setData(LocalDate data) { this.data = data; }
    public String getCategoria() { return categoria; }
    public void setCategoria(String categoria) { this.categoria = categoria; }
    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }
    public String getConta() { return conta; }
    public void setConta(String conta) { this.conta = conta; }
    public boolean isRecorrente() { return recorrente; }
    public void setRecorrente(boolean recorrente) { this.recorrente = recorrente; }
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getIdExterno() { return idExterno; }
    public void setIdExterno(String idExterno) { this.idExterno = idExterno; }
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public UUID getRecorrenciaOrigemId() { return recorrenciaOrigemId; }
    public void setRecorrenciaOrigemId(UUID recorrenciaOrigemId) { this.recorrenciaOrigemId = recorrenciaOrigemId; }
}
