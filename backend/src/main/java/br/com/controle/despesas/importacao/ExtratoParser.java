package br.com.controle.despesas.importacao;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le extratos bancarios e faturas de cartao em OFX (SGML 1.x ou XML 2.x) e CSV.
 * Nao acessa banco de dados nem rede: recebe bytes e devolve lancamentos normalizados.
 */
public final class ExtratoParser {
    static final int MAXIMO_LANCAMENTOS = 5000;
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final BigDecimal LIMITE_VALOR = new BigDecimal("999999999999.99");
    private static final Pattern BLOCO_OFX = Pattern.compile(
            "<STMTTRN>(.*?)(?=</STMTTRN>|<STMTTRN>|</BANKTRANLIST>|$)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final List<DateTimeFormatter> FORMATOS_DATA = List.of(
            DateTimeFormatter.ofPattern("dd/MM/uuuu"), DateTimeFormatter.ofPattern("dd/MM/uu"),
            DateTimeFormatter.ofPattern("dd-MM-uuuu"), DateTimeFormatter.ofPattern("dd.MM.uuuu"),
            DateTimeFormatter.ofPattern("uuuu-MM-dd"), DateTimeFormatter.ofPattern("uuuu/MM/dd"));

    private ExtratoParser() {}

    public record Lancamento(LocalDate data, String descricao, BigDecimal valor, String tipo, String idOrigem) {}

    public record Extrato(String formato, boolean cartao, String contaSugerida, String idConta, List<Lancamento> lancamentos) {}

    /**
     * @param cartaoCsv para CSV sem convencao reconhecivel: valores positivos sao compras (fatura de cartao).
     */
    public static Extrato ler(byte[] bytes, String nomeArquivo, boolean cartaoCsv) {
        if (bytes == null || bytes.length == 0) throw new ExtratoInvalidoException("O arquivo está vazio.");
        String inicio = new String(bytes, 0, Math.min(bytes.length, 2048), StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
        String nome = nomeArquivo == null ? "" : nomeArquivo.toLowerCase(Locale.ROOT);
        if (inicio.contains("OFXHEADER") || inicio.contains("<OFX>") || nome.endsWith(".ofx") || nome.endsWith(".qfx")) {
            return lerOfx(bytes);
        }
        return lerCsv(bytes, cartaoCsv);
    }

    // ---------- OFX ----------

    static Extrato lerOfx(byte[] bytes) {
        String texto = decodificarOfx(bytes);
        String maiusculo = texto.toUpperCase(Locale.ROOT);
        if (!maiusculo.contains("<OFX>")) throw new ExtratoInvalidoException("Arquivo OFX sem conteúdo <OFX>.");
        boolean cartao = maiusculo.contains("<CCSTMTRS>") || maiusculo.contains("<CCACCTFROM>");
        String org = campo(texto, "ORG");
        String idConta = campo(texto, "ACCTID");

        List<Lancamento> lancamentos = new ArrayList<>();
        Matcher m = BLOCO_OFX.matcher(texto);
        while (m.find()) {
            String bloco = m.group(1);
            LocalDate data = dataOfx(campo(bloco, "DTPOSTED"));
            BigDecimal valorAssinado = valor(campo(bloco, "TRNAMT"), false);
            if (data == null || valorAssinado == null || valorAssinado.signum() == 0) continue;
            String memo = campo(bloco, "MEMO"), nomeTrn = campo(bloco, "NAME");
            String descricao = !memo.isBlank() ? memo : nomeTrn;
            adicionar(lancamentos, data, descricao, valorAssinado, campo(bloco, "FITID"));
        }
        if (lancamentos.isEmpty()) throw new ExtratoInvalidoException("Nenhum lançamento encontrado no OFX.");
        String sugestao = org.isBlank() ? (cartao ? "Cartão" : "Conta") : org + (cartao ? " · cartão" : "");
        return new Extrato("OFX", cartao, sugestao, idConta, lancamentos);
    }

    private static String decodificarOfx(byte[] bytes) {
        String cabecalho = new String(bytes, 0, Math.min(bytes.length, 1024), StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
        if (cabecalho.contains("ENCODING=\"UTF-8\"") || cabecalho.contains("ENCODING:UTF-8") || cabecalho.contains("CHARSET:UTF-8")) {
            return semBom(new String(bytes, StandardCharsets.UTF_8));
        }
        if (cabecalho.contains("CHARSET:1252") || cabecalho.contains("CHARSET:ISO-8859-1") || cabecalho.contains("CHARSET:8859-1")) {
            return new String(bytes, WINDOWS_1252);
        }
        return decodificar(bytes);
    }

    /** Valor de uma tag OFX; em SGML a tag nao fecha e o valor termina na quebra de linha ou na proxima tag. */
    private static String campo(String texto, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">([^<\\r\\n]*)", Pattern.CASE_INSENSITIVE).matcher(texto);
        return m.find() ? desescapar(m.group(1).trim()) : "";
    }

    private static String desescapar(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");
    }

    private static LocalDate dataOfx(String bruto) {
        if (bruto.length() < 8) return null;
        try {
            return LocalDate.parse(bruto.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // ---------- CSV ----------

    static Extrato lerCsv(byte[] bytes, boolean cartaoInformado) {
        String texto = decodificar(bytes);
        String[] linhas = texto.split("\\r?\\n|\\r");

        int indiceCabecalho = -1;
        char separador = ',';
        Colunas colunas = null;
        for (int i = 0; i < Math.min(linhas.length, 30) && colunas == null; i++) {
            if (linhas[i].isBlank()) continue;
            char sep = separador(linhas[i]);
            Colunas c = Colunas.de(dividir(linhas[i], sep));
            if (c != null) { colunas = c; separador = sep; indiceCabecalho = i; }
        }
        if (colunas == null) {
            throw new ExtratoInvalidoException("CSV não reconhecido: o cabeçalho precisa ter colunas de data, descrição e valor.");
        }

        // Nubank (fatura): "date,title,amount" com compras positivas.
        boolean cartao = cartaoInformado || colunas.estiloFaturaNubank;
        boolean decimalVirgula = separador == ';';
        List<Lancamento> lancamentos = new ArrayList<>();
        for (int i = indiceCabecalho + 1; i < linhas.length; i++) {
            if (linhas[i].isBlank()) continue;
            List<String> celulas = dividir(linhas[i], separador);
            LocalDate data = dataCsv(celula(celulas, colunas.data));
            if (data == null) continue;
            String descricao = celula(celulas, colunas.descricao);
            if (normalizar(descricao).startsWith("saldo")) continue;

            BigDecimal valorAssinado;
            if (colunas.valor >= 0) {
                valorAssinado = valor(celula(celulas, colunas.valor), decimalVirgula);
                if (valorAssinado != null && cartao) valorAssinado = valorAssinado.negate();
            } else {
                BigDecimal credito = valor(celula(celulas, colunas.credito), decimalVirgula);
                BigDecimal debito = valor(celula(celulas, colunas.debito), decimalVirgula);
                if (credito == null && debito == null) continue;
                valorAssinado = (credito == null ? BigDecimal.ZERO : credito.abs())
                        .subtract(debito == null ? BigDecimal.ZERO : debito.abs());
            }
            if (valorAssinado == null || valorAssinado.signum() == 0) continue;
            adicionar(lancamentos, data, descricao, valorAssinado, celula(celulas, colunas.id));
        }
        if (lancamentos.isEmpty()) throw new ExtratoInvalidoException("Nenhum lançamento válido encontrado no CSV.");
        return new Extrato("CSV", cartao, "", "", lancamentos);
    }

    private record Colunas(int data, int descricao, int valor, int debito, int credito, int id, boolean estiloFaturaNubank) {
        static Colunas de(List<String> cabecalho) {
            int data = -1, descricao = -1, valor = -1, debito = -1, credito = -1, id = -1;
            boolean title = false, amount = false;
            for (int i = 0; i < cabecalho.size(); i++) {
                String h = normalizar(cabecalho.get(i));
                if (h.isEmpty()) continue;
                if (data < 0 && (h.equals("data") || h.equals("date") || h.startsWith("data ") || h.startsWith("data_"))) { data = i; continue; }
                if (h.contains("saldo")) continue;
                if (valor < 0 && (h.equals("valor") || h.equals("amount") || h.startsWith("valor ") || h.startsWith("valor(") || h.equals("value"))) {
                    valor = i; amount |= h.equals("amount"); continue;
                }
                if (debito < 0 && (h.equals("debito") || h.startsWith("debito ") || h.startsWith("saida"))) { debito = i; continue; }
                if (credito < 0 && (h.equals("credito") || h.startsWith("credito ") || h.startsWith("entrada"))) { credito = i; continue; }
                if (id < 0 && (h.equals("identificador") || h.equals("id") || h.equals("id da transacao"))) { id = i; continue; }
                if (descricao < 0 && (h.contains("descri") || h.equals("title") || h.contains("historico")
                        || h.contains("estabelecimento") || h.contains("lancamento") || h.equals("memo") || h.contains("detalhe"))) {
                    descricao = i; title |= h.equals("title");
                }
            }
            boolean temValor = valor >= 0 || debito >= 0 || credito >= 0;
            if (data < 0 || descricao < 0 || !temValor) return null;
            return new Colunas(data, descricao, valor, debito, credito, id, title && amount);
        }
    }

    private static char separador(String linha) {
        long pv = linha.chars().filter(c -> c == ';').count();
        long tab = linha.chars().filter(c -> c == '\t').count();
        long virgula = linha.chars().filter(c -> c == ',').count();
        if (pv >= virgula && pv >= tab && pv > 0) return ';';
        if (tab > virgula) return '\t';
        return ',';
    }

    static List<String> dividir(String linha, char sep) {
        List<String> out = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean aspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char c = linha.charAt(i);
            if (aspas) {
                if (c == '"' && i + 1 < linha.length() && linha.charAt(i + 1) == '"') { atual.append('"'); i++; }
                else if (c == '"') aspas = false;
                else atual.append(c);
            } else if (c == '"') {
                aspas = true;
            } else if (c == sep) {
                out.add(atual.toString().trim()); atual.setLength(0);
            } else {
                atual.append(c);
            }
        }
        out.add(atual.toString().trim());
        return out;
    }

    private static String celula(List<String> celulas, int indice) {
        return indice >= 0 && indice < celulas.size() ? celulas.get(indice) : "";
    }

    static LocalDate dataCsv(String bruto) {
        if (bruto == null || bruto.isBlank()) return null;
        String s = bruto.trim();
        if (s.length() > 10 && Character.isDigit(s.charAt(0))) s = s.split("[ T]")[0];
        for (DateTimeFormatter f : FORMATOS_DATA) {
            try { return LocalDate.parse(s, f); } catch (DateTimeParseException ignored) { /* proximo formato */ }
        }
        return null;
    }

    // ---------- comuns ----------

    /**
     * Converte "1.234,56", "-1234.56", "R$ 1.234,56", "(12,00)" ou "12,00-" em BigDecimal com sinal.
     * @param decimalVirgula se um unico ponto com tres casas deve ser lido como milhar (arquivos brasileiros com ';').
     */
    static BigDecimal valor(String bruto, boolean decimalVirgula) {
        if (bruto == null) return null;
        String s = bruto.replace("R$", "").replace(" ", "").replace(" ", "").replace("+", "").trim();
        if (s.isEmpty()) return null;
        boolean negativo = false;
        if (s.startsWith("(") && s.endsWith(")")) { negativo = true; s = s.substring(1, s.length() - 1); }
        if (s.endsWith("-")) { negativo = true; s = s.substring(0, s.length() - 1); }
        if (s.startsWith("-")) { negativo = !negativo; s = s.substring(1); }
        if (!s.matches("[0-9.,]+")) return null;

        int ultimaVirgula = s.lastIndexOf(','), ultimoPonto = s.lastIndexOf('.');
        if (ultimaVirgula >= 0 && ultimoPonto >= 0) {
            s = ultimaVirgula > ultimoPonto ? s.replace(".", "").replace(',', '.') : s.replace(",", "");
        } else if (ultimaVirgula >= 0) {
            s = s.chars().filter(c -> c == ',').count() == 1 ? s.replace(',', '.') : s.replace(",", "");
        } else if (ultimoPonto >= 0) {
            long pontos = s.chars().filter(c -> c == '.').count();
            boolean milhar = pontos > 1 || (decimalVirgula && s.length() - ultimoPonto - 1 == 3);
            if (milhar) s = s.replace(".", "");
        }
        try {
            BigDecimal v = new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
            if (v.compareTo(LIMITE_VALOR) > 0) return null;
            return negativo ? v.negate() : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void adicionar(List<Lancamento> lista, LocalDate data, String descricao, BigDecimal valorAssinado, String idOrigem) {
        if (lista.size() >= MAXIMO_LANCAMENTOS) {
            throw new ExtratoInvalidoException("O arquivo tem mais de " + MAXIMO_LANCAMENTOS + " lançamentos. Exporte um período menor.");
        }
        String desc = descricao == null ? "" : descricao.replaceAll("\\s+", " ").trim();
        if (desc.isEmpty()) desc = "Lançamento importado";
        String tipo = valorAssinado.signum() < 0 ? "SAIDA" : "ENTRADA";
        lista.add(new Lancamento(data, desc, valorAssinado.abs(), tipo, idOrigem == null ? "" : idOrigem.trim()));
    }

    /** UTF-8 estrito; se o arquivo nao for UTF-8 valido, usa windows-1252 (padrao de exportacoes de bancos brasileiros). */
    private static String decodificar(byte[] bytes) {
        try {
            return semBom(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            return new String(bytes, WINDOWS_1252);
        }
    }

    private static String semBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    /** Minusculas, sem acentos, sem pontuacao e com espacos simples. */
    public static String normalizar(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9()_ ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
