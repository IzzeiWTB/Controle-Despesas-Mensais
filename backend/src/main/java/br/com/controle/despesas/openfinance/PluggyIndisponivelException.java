package br.com.controle.despesas.openfinance;

/** Falha ao falar com a Pluggy; o controlador converte em 502. */
public class PluggyIndisponivelException extends RuntimeException {
    public PluggyIndisponivelException(String mensagem) {
        super(mensagem);
    }

    public PluggyIndisponivelException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
