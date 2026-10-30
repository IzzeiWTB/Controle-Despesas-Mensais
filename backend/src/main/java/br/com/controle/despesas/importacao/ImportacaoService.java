package br.com.controle.despesas.importacao;

import br.com.controle.despesas.Movimentacao;
import br.com.controle.despesas.MovimentacaoRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.*;

/**
 * Importa extratos e faturas (OFX/CSV) exportados pelo proprio banco.
 *
 * Cada lancamento recebe um idExterno estavel ("arq:" + SHA-256), derivado do FITID/identificador do banco
 * quando existe, ou do conteudo + ordem de ocorrencia no arquivo. Reimportar o mesmo arquivo, ou um periodo
 * sobreposto, nao duplica: o UNIQUE de movimentacao.id_externo garante isso tambem no banco.
 */
@Service
public class ImportacaoService {
    static final int MAXIMO_ITENS_RESPOSTA = 300;
    private static final int TAMANHO_DESCRICAO = 100;
    private static final int TAMANHO_CONTA = 60;
    private static final int LOTE_CONSULTA = 500;

    public enum Status { NOVO, JA_IMPORTADO, POSSIVEL_DUPLICADO, PAGAMENTO_FATURA }

    public record Item(LocalDate data, String descricao, BigDecimal valor, String tipo, String categoria, Status status) {}

    public record Resultado(String formato, boolean cartao, String conta, int total, int novos, int jaImportados,
                            int possiveisDuplicados, int pagamentosFatura, int importados, LocalDate dataMaisRecente,
                            int itensOmitidos, List<Item> itens) {}

    public record Opcoes(String conta, boolean cartaoCsv, boolean confirmar,
                         boolean incluirPossiveisDuplicados, boolean incluirPagamentosFatura) {}

    private final MovimentacaoRepository repository;
    private final CategorizadorLancamentos categorizador;

    public ImportacaoService(MovimentacaoRepository repository, CategorizadorLancamentos categorizador) {
        this.repository = repository;
        this.categorizador = categorizador;
    }

    /** Sem transacao propria: leitura e categorizacao (inclusive Gemini) ficam fora; saveAll e atomico. */
    public Resultado processar(byte[] bytes, String nomeArquivo, Opcoes opcoes) {
        ExtratoParser.Extrato extrato = ExtratoParser.ler(bytes, nomeArquivo, opcoes.cartaoCsv());
        String conta = limitar(primeiroPreenchido(opcoes.conta(), extrato.contaSugerida(), "Extrato importado"), TAMANHO_CONTA);
        List<ExtratoParser.Lancamento> lancamentos = extrato.lancamentos();

        List<String> ids = idsExternos(extrato, conta);
        Set<String> jaExistentes = existentes(ids);
        Map<String, Integer> semelhantes = semelhantesExistentes(lancamentos, new HashSet<>(ids));

        List<Status> status = new ArrayList<>(lancamentos.size());
        for (int i = 0; i < lancamentos.size(); i++) {
            ExtratoParser.Lancamento l = lancamentos.get(i);
            if (jaExistentes.contains(ids.get(i))) { status.add(Status.JA_IMPORTADO); continue; }
            if (CategorizadorLancamentos.ehPagamentoFatura(l.descricao())) { status.add(Status.PAGAMENTO_FATURA); continue; }
            String chave = chaveSemelhanca(l.data(), l.valor(), l.tipo());
            int restantes = semelhantes.getOrDefault(chave, 0);
            if (restantes > 0) { semelhantes.put(chave, restantes - 1); status.add(Status.POSSIVEL_DUPLICADO); }
            else status.add(Status.NOVO);
        }

        List<String> categorias = categorizador.categorizar(
                lancamentos.stream().map(ExtratoParser.Lancamento::descricao).toList(),
                lancamentos.stream().map(ExtratoParser.Lancamento::tipo).toList(),
                i -> status.get(i) != Status.JA_IMPORTADO);

        List<Movimentacao> novas = new ArrayList<>();
        LocalDate maisRecente = null;
        for (int i = 0; i < lancamentos.size(); i++) {
            if (!importavel(status.get(i), opcoes)) continue;
            ExtratoParser.Lancamento l = lancamentos.get(i);
            if (maisRecente == null || l.data().isAfter(maisRecente)) maisRecente = l.data();
            if (!opcoes.confirmar()) continue;
            Movimentacao m = new Movimentacao();
            m.setDescricao(limitar(l.descricao(), TAMANHO_DESCRICAO));
            m.setValor(l.valor());
            m.setData(l.data());
            m.setTipo(l.tipo());
            m.setCategoria(categorias.get(i));
            m.setConta(conta);
            m.setRecorrente(false);
            m.setIdExterno(ids.get(i));
            novas.add(m);
        }
        if (!novas.isEmpty()) repository.saveAll(novas);

        List<Item> itens = new ArrayList<>();
        for (int i = 0; i < lancamentos.size(); i++) {
            ExtratoParser.Lancamento l = lancamentos.get(i);
            itens.add(new Item(l.data(), limitar(l.descricao(), TAMANHO_DESCRICAO), l.valor(), l.tipo(), categorias.get(i), status.get(i)));
        }
        itens.sort(Comparator.comparing(Item::data).reversed());
        int omitidos = Math.max(0, itens.size() - MAXIMO_ITENS_RESPOSTA);
        return new Resultado(extrato.formato(), extrato.cartao(), conta, lancamentos.size(),
                contar(status, Status.NOVO), contar(status, Status.JA_IMPORTADO),
                contar(status, Status.POSSIVEL_DUPLICADO), contar(status, Status.PAGAMENTO_FATURA),
                novas.size(), maisRecente, omitidos, itens.subList(0, itens.size() - omitidos));
    }

    private static boolean importavel(Status s, Opcoes o) {
        return switch (s) {
            case NOVO -> true;
            case POSSIVEL_DUPLICADO -> o.incluirPossiveisDuplicados();
            case PAGAMENTO_FATURA -> o.incluirPagamentosFatura();
            case JA_IMPORTADO -> false;
        };
    }

    /**
     * Escopo = conta do banco (ACCTID do OFX) ou nome da conta informado. Com FITID/identificador unico no
     * arquivo, a chave e o proprio id; sem ele (ou se o banco repetir o FITID), conteudo + ordem de ocorrencia.
     */
    private static List<String> idsExternos(ExtratoParser.Extrato extrato, String conta) {
        String escopo = extrato.formato() + "|" + (extrato.idConta().isBlank() ? ExtratoParser.normalizar(conta) : extrato.idConta());
        Map<String, Long> contagemIds = new HashMap<>();
        for (ExtratoParser.Lancamento l : extrato.lancamentos()) {
            if (!l.idOrigem().isBlank()) contagemIds.merge(l.idOrigem(), 1L, Long::sum);
        }
        Map<String, Integer> ocorrencias = new HashMap<>();
        List<String> ids = new ArrayList<>(extrato.lancamentos().size());
        for (ExtratoParser.Lancamento l : extrato.lancamentos()) {
            String chave;
            if (!l.idOrigem().isBlank() && contagemIds.get(l.idOrigem()) == 1) {
                chave = escopo + "|id|" + l.idOrigem();
            } else {
                String conteudo = escopo + "|" + l.data() + "|" + l.valor().toPlainString() + "|" + l.tipo() + "|"
                        + ExtratoParser.normalizar(l.descricao());
                int n = ocorrencias.merge(conteudo, 1, Integer::sum);
                chave = conteudo + "|" + n;
            }
            ids.add("arq:" + sha256(chave));
        }
        return ids;
    }

    private Set<String> existentes(List<String> ids) {
        Set<String> encontrados = new HashSet<>();
        List<String> unicos = new ArrayList<>(new LinkedHashSet<>(ids));
        for (int i = 0; i < unicos.size(); i += LOTE_CONSULTA) {
            for (Movimentacao m : repository.findByIdExternoIn(unicos.subList(i, Math.min(unicos.size(), i + LOTE_CONSULTA)))) {
                encontrados.add(m.getIdExterno());
            }
        }
        return encontrados;
    }

    /** Lancamentos ja cadastrados por outra origem (manual, Pluggy, outro arquivo) com mesma data, valor e tipo. */
    private Map<String, Integer> semelhantesExistentes(List<ExtratoParser.Lancamento> lancamentos, Set<String> idsDoArquivo) {
        LocalDate inicio = lancamentos.stream().map(ExtratoParser.Lancamento::data).min(LocalDate::compareTo).orElseThrow();
        LocalDate fim = lancamentos.stream().map(ExtratoParser.Lancamento::data).max(LocalDate::compareTo).orElseThrow();
        Map<String, Integer> contagem = new HashMap<>();
        for (Movimentacao m : repository.findByDataBetweenOrderByDataDesc(inicio, fim)) {
            if (m.getIdExterno() != null && idsDoArquivo.contains(m.getIdExterno())) continue;
            contagem.merge(chaveSemelhanca(m.getData(), m.getValor(), m.getTipo()), 1, Integer::sum);
        }
        return contagem;
    }

    private static String chaveSemelhanca(LocalDate data, BigDecimal valor, String tipo) {
        return data + "|" + valor.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "|" + tipo;
    }

    private static int contar(List<Status> status, Status alvo) {
        return (int) status.stream().filter(s -> s == alvo).count();
    }

    private static String primeiroPreenchido(String... valores) {
        for (String v : valores) if (v != null && !v.isBlank()) return v.trim();
        return "";
    }

    private static String limitar(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max).trim();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
