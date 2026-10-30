package br.com.controle.despesas.importacao;

/** Arquivo de extrato que nao pode ser lido; a mensagem e exibida ao usuario. */
public class ExtratoInvalidoException extends RuntimeException {
    public ExtratoInvalidoException(String mensagem) {
        super(mensagem);
    }
}
