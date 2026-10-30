package br.com.controle.despesas.importacao;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

class ExtratoParserTest {
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    static final String OFX_CONTA_SGML = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            ENCODING:USASCII
            CHARSET:1252

            <OFX>
            <SIGNONMSGSRSV1><SONRS><FI><ORG>Banco Exemplo<FID>999</FI></SONRS></SIGNONMSGSRSV1>
            <BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>BRL
            <BANKACCTFROM><BANKID>999<ACCTID>12345-6<ACCTTYPE>CHECKING</BANKACCTFROM>
            <BANKTRANLIST><DTSTART>20261001<DTEND>20261031
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261005120000[-3:BRT]<TRNAMT>-89.90<FITID>A1<MEMO>Padaria São João
            <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261006<TRNAMT>5000,00<FITID>A2<MEMO>SALARIO EMPRESA
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261007<TRNAMT>0.00<FITID>A3<MEMO>Tarifa zerada
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261008<TRNAMT>-1200.00<FITID>A4<NAME>Pagamento de fatura
            </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1>
            </OFX>
            """;

    static final String OFX_CARTAO_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <?OFX OFXHEADER="200" VERSION="220"?>
            <OFX><CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS><CURDEF>BRL</CURDEF>
            <CCACCTFROM><ACCTID>cartao-9</ACCTID></CCACCTFROM>
            <BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20261003</DTPOSTED><TRNAMT>-45.50</TRNAMT><FITID>c1</FITID><MEMO>Uber *Viagem</MEMO></STMTTRN>
            <STMTTRN><TRNTYPE>CREDIT</TRNTYPE><DTPOSTED>20261010</DTPOSTED><TRNAMT>300.00</TRNAMT><FITID>c2</FITID><MEMO>Pagamento recebido</MEMO></STMTTRN>
            </BANKTRANLIST></CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1></OFX>
            """;

    @Test void leOfxSgmlEmWindows1252() {
        var e = ExtratoParser.ler(OFX_CONTA_SGML.getBytes(WINDOWS_1252), "extrato.ofx", false);
        assertThat(e.formato()).isEqualTo("OFX");
        assertThat(e.cartao()).isFalse();
        assertThat(e.idConta()).isEqualTo("12345-6");
        assertThat(e.contaSugerida()).isEqualTo("Banco Exemplo");
        assertThat(e.lancamentos()).hasSize(3); // valor zero e ignorado
        var padaria = e.lancamentos().get(0);
        assertThat(padaria.data()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(padaria.descricao()).isEqualTo("Padaria São João");
        assertThat(padaria.valor()).isEqualByComparingTo("89.90");
        assertThat(padaria.tipo()).isEqualTo("SAIDA");
        assertThat(padaria.idOrigem()).isEqualTo("A1");
        assertThat(e.lancamentos().get(1).valor()).isEqualByComparingTo("5000.00");
        assertThat(e.lancamentos().get(1).tipo()).isEqualTo("ENTRADA");
        assertThat(e.lancamentos().get(2).descricao()).isEqualTo("Pagamento de fatura"); // NAME quando nao ha MEMO
    }

    @Test void leOfxXmlDeCartao() {
        var e = ExtratoParser.ler(OFX_CARTAO_XML.getBytes(StandardCharsets.UTF_8), "fatura.txt", false);
        assertThat(e.cartao()).isTrue();
        assertThat(e.idConta()).isEqualTo("cartao-9");
        assertThat(e.lancamentos()).extracting(ExtratoParser.Lancamento::tipo).containsExactly("SAIDA", "ENTRADA");
        assertThat(e.lancamentos().get(0).descricao()).isEqualTo("Uber *Viagem");
    }

    @Test void leCsvDeFaturaNoFormatoDateTitleAmount() {
        String csv = "date,title,amount\n2026-10-02,Netflix.com,55.90\n2026-10-04,\"Mercado, Centro\",120.35\n2026-10-09,Pagamento recebido,-500.00\n";
        var e = ExtratoParser.ler(csv.getBytes(StandardCharsets.UTF_8), "Nubank_2026-10.csv", false);
        assertThat(e.cartao()).isTrue();
        assertThat(e.lancamentos()).extracting(ExtratoParser.Lancamento::tipo).containsExactly("SAIDA", "SAIDA", "ENTRADA");
        assertThat(e.lancamentos().get(1).descricao()).isEqualTo("Mercado, Centro");
        assertThat(e.lancamentos().get(1).valor()).isEqualByComparingTo("120.35");
    }

    @Test void leCsvDeContaComIdentificador() {
        String csv = "﻿Data,Valor,Identificador,Descrição\n01/10/2026,-35.00,abc-1,Compra no débito - Farmácia\n03/10/2026,1500.00,abc-2,Transferência recebida\n";
        var e = ExtratoParser.ler(csv.getBytes(StandardCharsets.UTF_8), "extrato.csv", false);
        assertThat(e.cartao()).isFalse();
        assertThat(e.lancamentos()).hasSize(2);
        assertThat(e.lancamentos().get(0).idOrigem()).isEqualTo("abc-1");
        assertThat(e.lancamentos().get(0).tipo()).isEqualTo("SAIDA");
        assertThat(e.lancamentos().get(0).descricao()).isEqualTo("Compra no débito - Farmácia");
    }

    @Test void leCsvBrasileiroComPreambuloPontoEVirgulaESaldo() {
        String csv = "Extrato Conta Corrente\nConta ;1234\nPeríodo ;01/10/2026 a 31/10/2026\n\n"
                + "Data Lançamento;Histórico;Descrição;Valor;Saldo\n"
                + "05/10/2026;Pix enviado;Fulano;-1.234,56;10.000,00\n"
                + "06/10/2026;Saldo do dia;;0,00;10.000,00\n"
                + "07/10/2026;Pix recebido;Ciclano;2.000;12.000,00\n";
        var e = ExtratoParser.ler(csv.getBytes(WINDOWS_1252), "Extrato.csv", false);
        assertThat(e.lancamentos()).hasSize(2);
        assertThat(e.lancamentos().get(0).valor()).isEqualByComparingTo("1234.56");
        assertThat(e.lancamentos().get(0).descricao()).isEqualTo("Pix enviado");
        assertThat(e.lancamentos().get(1).valor()).isEqualByComparingTo("2000.00");
        assertThat(e.lancamentos().get(1).tipo()).isEqualTo("ENTRADA");
    }

    @Test void leCsvComColunasDebitoECredito() {
        String csv = "Data;Descrição;Débito;Crédito\n10/10/2026;Conta de luz;150,00;\n11/10/2026;Reembolso;;80,00\n";
        var e = ExtratoParser.ler(csv.getBytes(StandardCharsets.UTF_8), "x.csv", false);
        assertThat(e.lancamentos()).extracting(ExtratoParser.Lancamento::tipo).containsExactly("SAIDA", "ENTRADA");
        assertThat(e.lancamentos()).extracting(ExtratoParser.Lancamento::valor)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(new BigDecimal("150.00"), new BigDecimal("80.00"));
    }

    @Test void cartaoInformadoInverteSinalDeCsvGenerico() {
        String csv = "Data;Estabelecimento;Valor\n02/10/2026;Posto Shell;200,00\n";
        var e = ExtratoParser.ler(csv.getBytes(StandardCharsets.UTF_8), "fatura.csv", true);
        assertThat(e.lancamentos().get(0).tipo()).isEqualTo("SAIDA");
    }

    @Test void converteValoresEmFormatosVariados() {
        assertThat(ExtratoParser.valor("R$ 1.234,56", true)).isEqualByComparingTo("1234.56");
        assertThat(ExtratoParser.valor("-1,234.56", false)).isEqualByComparingTo("-1234.56");
        assertThat(ExtratoParser.valor("(12,00)", true)).isEqualByComparingTo("-12.00");
        assertThat(ExtratoParser.valor("12,00-", true)).isEqualByComparingTo("-12.00");
        assertThat(ExtratoParser.valor("10.567", false)).isEqualByComparingTo("10.57");
        assertThat(ExtratoParser.valor("abc", false)).isNull();
        assertThat(ExtratoParser.valor("", false)).isNull();
    }

    @Test void converteDatasEmFormatosVariados() {
        assertThat(ExtratoParser.dataCsv("05/10/2026")).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(ExtratoParser.dataCsv("05/10/26")).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(ExtratoParser.dataCsv("2026-10-05")).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(ExtratoParser.dataCsv("2026-10-05T10:00:00")).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(ExtratoParser.dataCsv("Saldo anterior")).isNull();
    }

    @Test void rejeitaArquivosNaoReconhecidos() {
        assertThatThrownBy(() -> ExtratoParser.ler(new byte[0], "a.csv", false)).isInstanceOf(ExtratoInvalidoException.class);
        assertThatThrownBy(() -> ExtratoParser.ler("nome,idade\nAna,30\n".getBytes(StandardCharsets.UTF_8), "a.csv", false))
                .isInstanceOf(ExtratoInvalidoException.class).hasMessageContaining("cabeçalho");
        assertThatThrownBy(() -> ExtratoParser.ler("OFXHEADER:100\n<OFX></OFX>".getBytes(StandardCharsets.UTF_8), "a.ofx", false))
                .isInstanceOf(ExtratoInvalidoException.class);
    }
}
