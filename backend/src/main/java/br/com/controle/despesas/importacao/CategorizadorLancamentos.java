package br.com.controle.despesas.importacao;

import br.com.controle.despesas.Movimentacao;
import br.com.controle.despesas.MovimentacaoRepository;
import br.com.controle.despesas.ia.GeminiService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/**
 * Categoriza lancamentos importados (arquivos OFX/CSV e Pluggy):
 * historico do usuario (descricao igual, ultimos 400 dias) > regras locais > Gemini em lote para o que sobrar.
 */
@Component
public class CategorizadorLancamentos {
    private static final Pattern PAGAMENTO_FATURA = Pattern.compile(
            "\\b(pagamento recebido|pagamento (de |da |do )?fatura|pagto (de |da )?fatura|pgto (de |da )?fatura"
                    + "|pag fatura|pagamento (do |de )?cartao|pagto cartao|fatura (do )?cartao)\\b");

    private final MovimentacaoRepository repository;
    private final GeminiService gemini;
    private final Clock clock;

    public CategorizadorLancamentos(MovimentacaoRepository repository, GeminiService gemini, Clock clock) {
        this.repository = repository;
        this.gemini = gemini;
        this.clock = clock;
    }

    /** Pagamento de fatura de cartao: as compras ja entram pela fatura, importar o pagamento contaria o gasto duas vezes. */
    public static boolean ehPagamentoFatura(String descricao) {
        return PAGAMENTO_FATURA.matcher(ExtratoParser.normalizar(descricao)).find();
    }

    /**
     * @param podeUsarIa indices cujas descricoes podem ir ao Gemini (ex.: so lancamentos ainda nao importados).
     */
    public List<String> categorizar(List<String> descricoes, List<String> tipos, IntPredicate podeUsarIa) {
        LocalDate hoje = LocalDate.now(clock);
        Map<String, String> historico = new HashMap<>();
        for (Movimentacao m : repository.findByDataBetweenOrderByDataDesc(hoje.minusDays(400), hoje.plusDays(31))) {
            if (m.getCategoria() != null && !"Outros".equals(m.getCategoria())) {
                historico.putIfAbsent(ExtratoParser.normalizar(m.getDescricao()), m.getCategoria());
            }
        }
        List<String> categorias = new ArrayList<>(descricoes.size());
        List<String> pendentes = new ArrayList<>();
        for (int i = 0; i < descricoes.size(); i++) {
            String cat = historico.get(ExtratoParser.normalizar(descricoes.get(i)));
            if (cat == null) cat = GeminiService.categorizarHeuristica(descricoes.get(i));
            if ("Salário".equals(cat) && "SAIDA".equals(tipos.get(i))) cat = "Outros";
            categorias.add(cat);
            if ("Outros".equals(cat) && podeUsarIa.test(i)) pendentes.add(descricoes.get(i));
        }
        if (!pendentes.isEmpty()) {
            Map<String, String> ia = gemini.categorizarLote(pendentes);
            for (int i = 0; i < descricoes.size(); i++) {
                String sugerida = ia.get(descricoes.get(i));
                if (sugerida != null && "Outros".equals(categorias.get(i))
                        && !("Salário".equals(sugerida) && "SAIDA".equals(tipos.get(i)))) {
                    categorias.set(i, sugerida);
                }
            }
        }
        return categorias;
    }
}
