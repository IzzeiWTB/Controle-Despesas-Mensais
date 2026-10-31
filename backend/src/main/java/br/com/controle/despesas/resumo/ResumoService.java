package br.com.controle.despesas.resumo;

import br.com.controle.despesas.Movimentacao;
import br.com.controle.despesas.MovimentacaoRepository;
import br.com.controle.despesas.importacao.ExtratoParser;
import br.com.controle.despesas.openfinance.ContaConectada;
import br.com.controle.despesas.openfinance.ContaConectadaRepository;
import br.com.controle.despesas.openfinance.Investimento;
import br.com.controle.despesas.openfinance.InvestimentoRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Visao geral do dinheiro: patrimonio pelas contas conectadas (saldo, faturas, investimentos),
 * lancamentos previstos para o mes e projecao do fim do mes.
 *
 * Previstos (so para o mes atual ou futuros), sem duplicar o que ja foi lancado no periodo:
 * - RECORRENTE: marcado como recorrente no mes anterior e ainda nao trazido;
 * - PARCELA: compra parcelada "N/M" no mes anterior com N &lt; M (proxima parcela);
 * - DETECTADO: mesma descricao em pelo menos 2 dos 3 meses anteriores, ate 2 vezes por mes e valor estavel
 *   (contas fixas, assinaturas, salario).
 */
@Service
public class ResumoService {
    static final Pattern PARCELA = Pattern.compile("\\b(\\d{1,2})\\s*/\\s*(\\d{1,2})\\b");
    private static final int MESES_HISTORICO = 3;
    private static final BigDecimal VARIACAO_MAXIMA = new BigDecimal("1.5");
    private static final int DIA_MINIMO_PARA_RITMO = 5;

    private final MovimentacaoRepository movimentacoes;
    private final ContaConectadaRepository contas;
    private final InvestimentoRepository investimentos;
    private final Clock clock;

    public ResumoService(MovimentacaoRepository movimentacoes, ContaConectadaRepository contas,
                         InvestimentoRepository investimentos, Clock clock) {
        this.movimentacoes = movimentacoes;
        this.contas = contas;
        this.investimentos = investimentos;
        this.clock = clock;
    }

    public record Previsto(String descricao, BigDecimal valor, String tipo, LocalDate dataPrevista,
                           String categoria, String conta, String origem) {}

    public Map<String, Object> resumo(YearMonth periodo) {
        LocalDate hoje = LocalDate.now(clock);
        YearMonth mesAtual = YearMonth.from(hoje);
        Map<String, Object> patrimonio = patrimonio();

        List<Movimentacao> doPeriodo = movimentacoes.findByDataBetweenOrderByDataDesc(periodo.atDay(1), periodo.atEndOfMonth());
        List<Previsto> previstos = periodo.isBefore(mesAtual) ? List.of() : previstos(periodo, doPeriodo);
        BigDecimal saidasPrevistas = soma(previstos, "SAIDA");
        BigDecimal entradasPrevistas = soma(previstos, "ENTRADA");

        Map<String, Object> resposta = new LinkedHashMap<>();
        resposta.put("periodo", periodo.toString());
        resposta.put("mesAtual", periodo.equals(mesAtual));
        resposta.put("patrimonio", patrimonio);
        resposta.put("previstos", previstos);
        resposta.put("totalPrevistoSaidas", saidasPrevistas);
        resposta.put("totalPrevistoEntradas", entradasPrevistas);
        // Saldo das contas hoje + entradas previstas - gastos previstos - faturas abertas.
        resposta.put("saldoPrevistoFimMes", periodo.equals(mesAtual) && (boolean) patrimonio.get("temContas")
                ? ((BigDecimal) patrimonio.get("saldoContas")).add(entradasPrevistas).subtract(saidasPrevistas)
                        .subtract((BigDecimal) patrimonio.get("faturasAbertas"))
                : null);
        resposta.put("ritmo", periodo.equals(mesAtual) && hoje.getDayOfMonth() >= DIA_MINIMO_PARA_RITMO
                ? ritmo(doPeriodo, hoje) : null);
        return resposta;
    }

    private Map<String, Object> patrimonio() {
        BigDecimal saldoContas = BigDecimal.ZERO, faturas = BigDecimal.ZERO, limiteDisponivel = BigDecimal.ZERO;
        int quantidadeContas = 0, quantidadeCartoes = 0;
        LocalDateTime atualizadoEm = null;
        for (ContaConectada c : contas.findAll()) {
            if ("CARTAO_CREDITO".equals(c.getTipo())) {
                quantidadeCartoes++;
                BigDecimal fatura = positivo(c.getFaturaAtual());
                faturas = faturas.add(fatura);
                if (c.getLimiteCredito() != null) limiteDisponivel = limiteDisponivel.add(positivo(c.getLimiteCredito().subtract(fatura)));
            } else {
                quantidadeContas++;
                if (c.getSaldo() != null) saldoContas = saldoContas.add(c.getSaldo());
            }
            if (c.getAtualizadoEm() != null && (atualizadoEm == null || c.getAtualizadoEm().isAfter(atualizadoEm))) atualizadoEm = c.getAtualizadoEm();
        }
        BigDecimal investido = investimentos.findAll().stream()
                .map(Investimento::getValorInvestido).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("temContas", quantidadeContas + quantidadeCartoes > 0);
        p.put("quantidadeContas", quantidadeContas);
        p.put("quantidadeCartoes", quantidadeCartoes);
        p.put("saldoContas", saldoContas);
        p.put("faturasAbertas", faturas);
        p.put("limiteDisponivel", limiteDisponivel);
        p.put("investimentos", investido);
        p.put("patrimonioLiquido", saldoContas.add(investido).subtract(faturas));
        p.put("atualizadoEm", atualizadoEm);
        return p;
    }

    private List<Previsto> previstos(YearMonth periodo, List<Movimentacao> doPeriodo) {
        Set<String> jaLancadas = doPeriodo.stream().map(ResumoService::chave).collect(Collectors.toSet());
        Set<UUID> origensTrazidas = doPeriodo.stream().map(Movimentacao::getRecorrenciaOrigemId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        YearMonth anterior = periodo.minusMonths(1);
        List<Movimentacao> historico = movimentacoes.findByDataBetweenOrderByDataDesc(
                periodo.minusMonths(MESES_HISTORICO).atDay(1), periodo.atDay(1).minusDays(1));

        Map<String, Previsto> previstos = new LinkedHashMap<>();
        // Mais recente primeiro: vale a ultima ocorrencia de cada descricao.
        for (Movimentacao m : historico) {
            if (!YearMonth.from(m.getData()).equals(anterior)) continue;
            String chave = chave(m);
            if (jaLancadas.contains(chave) || previstos.containsKey(chave)) continue;
            String proximaParcela = proximaParcela(m.getDescricao());
            if (proximaParcela != null) {
                previstos.put(chave, previsto(m, proximaParcela, periodo, "PARCELA"));
            } else if (m.isRecorrente() && !origensTrazidas.contains(m.getId())) {
                previstos.put(chave, previsto(m, m.getDescricao(), periodo, "RECORRENTE"));
            }
        }

        Map<String, List<Movimentacao>> porDescricao = historico.stream()
                .filter(m -> PARCELA.matcher(m.getDescricao()).find() == false)
                .collect(Collectors.groupingBy(ResumoService::chave, LinkedHashMap::new, Collectors.toList()));
        porDescricao.forEach((chave, ocorrencias) -> {
            if (chave.endsWith("|") || jaLancadas.contains(chave) || previstos.containsKey(chave)) return;
            Map<YearMonth, Long> porMes = ocorrencias.stream()
                    .collect(Collectors.groupingBy(m -> YearMonth.from(m.getData()), Collectors.counting()));
            if (porMes.size() < 2 || porMes.values().stream().anyMatch(n -> n > 2)) return;
            if (!porMes.containsKey(anterior) && !porMes.containsKey(anterior.minusMonths(1))) return;
            BigDecimal menor = ocorrencias.stream().map(Movimentacao::getValor).min(BigDecimal::compareTo).orElseThrow();
            BigDecimal maior = ocorrencias.stream().map(Movimentacao::getValor).max(BigDecimal::compareTo).orElseThrow();
            if (menor.signum() <= 0 || maior.compareTo(menor.multiply(VARIACAO_MAXIMA)) > 0) return;
            Movimentacao ultima = ocorrencias.get(0);
            previstos.put(chave, previsto(ultima, ultima.getDescricao(), periodo, "DETECTADO"));
        });

        return previstos.values().stream()
                .sorted(Comparator.comparing(Previsto::dataPrevista).thenComparing(Previsto::descricao))
                .toList();
    }

    private Map<String, Object> ritmo(List<Movimentacao> doPeriodo, LocalDate hoje) {
        BigDecimal despesas = doPeriodo.stream()
                .filter(m -> "SAIDA".equals(m.getTipo()) && !m.getData().isAfter(hoje))
                .map(Movimentacao::getValor).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mediaDiaria = despesas.divide(BigDecimal.valueOf(hoje.getDayOfMonth()), 2, RoundingMode.HALF_UP);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("despesasAteHoje", despesas);
        r.put("mediaDiaria", mediaDiaria);
        r.put("projecaoDespesasMes", mediaDiaria.multiply(BigDecimal.valueOf(hoje.lengthOfMonth())).setScale(2, RoundingMode.HALF_UP));
        r.put("diasRestantes", hoje.lengthOfMonth() - hoje.getDayOfMonth());
        return r;
    }

    private static Previsto previsto(Movimentacao m, String descricao, YearMonth periodo, String origem) {
        int dia = Math.min(m.getData().getDayOfMonth(), periodo.lengthOfMonth());
        return new Previsto(descricao, m.getValor(), m.getTipo(), periodo.atDay(dia), m.getCategoria(), m.getConta(), origem);
    }

    /** "LOJA X 03/10" vira "LOJA X 04/10"; null se nao for parcela ou se for a ultima. */
    static String proximaParcela(String descricao) {
        Matcher parcela = PARCELA.matcher(descricao == null ? "" : descricao);
        if (!parcela.find()) return null;
        int atual = Integer.parseInt(parcela.group(1)), total = Integer.parseInt(parcela.group(2));
        if (atual < 1 || total < 2 || atual >= total) return null;
        String proxima = String.format("%0" + parcela.group(1).length() + "d", atual + 1) + "/" + parcela.group(2);
        return descricao.substring(0, parcela.start()) + proxima + descricao.substring(parcela.end());
    }

    /** Tipo + descricao sem acentos, numeros e pontuacao: "NETFLIX.COM 10/12" e "Netflix com" coincidem. */
    static String chave(Movimentacao m) {
        String texto = ExtratoParser.normalizar(m.getDescricao()).replaceAll("[0-9()_]", " ").replaceAll("\\s+", " ").trim();
        return m.getTipo() + "|" + texto;
    }

    private static BigDecimal soma(List<Previsto> previstos, String tipo) {
        return previstos.stream().filter(p -> tipo.equals(p.tipo())).map(Previsto::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal positivo(BigDecimal valor) {
        return valor == null || valor.signum() < 0 ? BigDecimal.ZERO : valor;
    }
}
