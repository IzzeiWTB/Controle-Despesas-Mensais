package br.com.controle.despesas.openfinance;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Conexao Pluggy (um banco) que o Clareza sincroniza, inclusive itens do Meu Pluggy para uso pessoal. */
@Entity
@Table(name = "item_pluggy")
public class ItemPluggy {

    @Id
    @Column(name = "item_id", length = 100)
    private String itemId;

    /** Apelido informado pelo usuario (ex.: "Nubank"); tem prioridade sobre o nome do conector. */
    @Column(length = 60)
    private String nome;

    @Column(length = 80)
    private String instituicao;

    @Column(name = "status_pluggy", length = 40)
    private String statusPluggy;

    @Column(name = "consentimento_expira_em")
    private LocalDateTime consentimentoExpiraEm;

    @Column(name = "ultima_sincronizacao")
    private LocalDateTime ultimaSincronizacao;

    @Column(name = "ultimo_erro", length = 255)
    private String ultimoErro;

    @Column(name = "criado_em", nullable = false)
    private LocalDateTime criadoEm;

    protected ItemPluggy() {}

    public ItemPluggy(String itemId, LocalDateTime criadoEm) {
        this.itemId = itemId;
        this.criadoEm = criadoEm;
    }

    public String getItemId() { return itemId; }
    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }
    public String getInstituicao() { return instituicao; }
    public void setInstituicao(String instituicao) { this.instituicao = instituicao; }
    public String getStatusPluggy() { return statusPluggy; }
    public void setStatusPluggy(String statusPluggy) { this.statusPluggy = statusPluggy; }
    public LocalDateTime getConsentimentoExpiraEm() { return consentimentoExpiraEm; }
    public void setConsentimentoExpiraEm(LocalDateTime consentimentoExpiraEm) { this.consentimentoExpiraEm = consentimentoExpiraEm; }
    public LocalDateTime getUltimaSincronizacao() { return ultimaSincronizacao; }
    public void setUltimaSincronizacao(LocalDateTime ultimaSincronizacao) { this.ultimaSincronizacao = ultimaSincronizacao; }
    public String getUltimoErro() { return ultimoErro; }
    public void setUltimoErro(String ultimoErro) {
        this.ultimoErro = ultimoErro == null || ultimoErro.length() <= 255 ? ultimoErro : ultimoErro.substring(0, 255);
    }
    public LocalDateTime getCriadoEm() { return criadoEm; }

    /** Nome exibido: apelido do usuario, senao a instituicao detectada. */
    public String getRotulo() {
        if (nome != null && !nome.isBlank()) return nome;
        return instituicao != null && !instituicao.isBlank() ? instituicao : "Banco";
    }
}
