# Importar extratos e faturas (OFX/CSV)

Caminho para ter dados reais de bancos e cartões no Clareza **sem depender de contrato de produção com a Pluggy**: o próprio banco exporta o arquivo e o Clareza o lê.

## Como usar

1. No app ou internet banking, exporte:
   - **Conta corrente/poupança**: extrato em **OFX** (preferível) ou CSV.
   - **Cartão de crédito**: fatura em **OFX** ou CSV.
   Os menus variam por banco; procure por "Exportar", "Baixar extrato", "OFX", "Money/Quicken" ou "Exportar fatura". Alguns bancos (como o Nubank) enviam o arquivo por e-mail.
2. No Clareza: **Movimentações → Importar extrato** (ou, no Open Finance, "importe o arquivo OFX/CSV").
3. Escolha o arquivo e, opcionalmente, o nome da conta/cartão (ex.: "Nubank cartão"). Para OFX o nome do banco é sugerido automaticamente.
4. **Pré-visualizar**: nada é gravado. Confira os lançamentos, categorias e avisos.
5. **Importar N lançamento(s)**: grava os lançamentos novos e abre o mês do lançamento mais recente.

## O que o importador faz

- **Formatos**: OFX 1.x (SGML) e 2.x (XML) de conta e de cartão; CSV com `,`, `;` ou tabulação, com ou sem linhas de cabeçalho do banco antes da tabela. Reconhece colunas como `Data`/`date`, `Descrição`/`Histórico`/`title`/`Estabelecimento`, `Valor`/`amount` ou `Débito` + `Crédito`, e `Identificador`. Valores como `1.234,56`, `-1234.56`, `R$ 1.234,56` e `(12,00)`; datas `dd/mm/aaaa`, `dd/mm/aa` e `aaaa-mm-dd`. Arquivos em UTF-8 ou Windows-1252.
- **Sinal dos valores**: em OFX e extratos de conta, negativo = despesa. Em fatura CSV no formato `date,title,amount` as compras são positivas e isso é detectado sozinho; para outro CSV de fatura com compras positivas, marque "CSV de fatura de cartão".
- **Sem duplicação**: cada lançamento recebe um identificador estável (FITID/identificador do banco ou conteúdo + ordem no arquivo). Reimportar o mesmo arquivo ou um período sobreposto não duplica; o banco de dados garante isso com restrição única.
- **Possíveis duplicados**: lançamento com mesma data, valor e tipo de um já cadastrado à mão, pela Pluggy ou por outro arquivo fica fora por padrão; marque a opção para importá-lo assim mesmo.
- **Pagamento de fatura**: "Pagamento recebido", "Pagamento de fatura" etc. ficam fora por padrão, porque as compras já entram pela fatura do cartão e o gasto contaria duas vezes. Se você importa só o extrato da conta (sem a fatura), marque a opção para incluí-los.
- **Categorias**: primeiro o seu histórico (mesma descrição já categorizada nos últimos 400 dias), depois regras locais e, para o que sobrar como "Outros", uma única chamada ao Gemini quando `GEMINI_API_KEY` estiver configurada. Sem a chave, nenhuma descrição sai do servidor.
- **Limites**: arquivo de até 2 MB e 5.000 lançamentos; a pré-visualização mostra os 300 mais recentes.

## API

`POST /api/importacoes/extrato` (multipart, cabeçalho `X-API-Key`)

| Campo | Padrão | Uso |
| --- | --- | --- |
| `arquivo` | — | OFX/QFX/CSV |
| `conta` | sugestão do OFX ou "Extrato importado" | rótulo da conta/cartão (até 60 caracteres) |
| `cartao` | `false` | CSV genérico com compras positivas |
| `confirmar` | `false` | `false` = pré-visualização; `true` = grava |
| `incluirPossiveisDuplicados` | `false` | grava também os possíveis duplicados |
| `incluirPagamentosFatura` | `false` | grava também pagamentos de fatura |

Resposta: `formato`, `cartao`, `conta`, `total`, `novos`, `jaImportados`, `possiveisDuplicados`, `pagamentosFatura`, `importados`, `dataMaisRecente`, `itensOmitidos` e `itens` (`data`, `descricao`, `valor`, `tipo`, `categoria`, `status`). Arquivo não reconhecido: 422 com `{"erro": "..."}`; acima de 2 MB: 413.

## Limitações conhecidas

- Testado com arquivos de exemplo nos formatos acima, **não com arquivos reais de cada banco**. Se um banco gerar um layout não reconhecido, a mensagem de erro aparece na tela e o parser (`backend/src/main/java/br/com/controle/despesas/importacao/ExtratoParser.java`) pode ser ajustado com um exemplo anonimizado.
- Não importa saldo, limite de cartão nem investimentos: só lançamentos.
- A importação é manual (você baixa o arquivo). Atualização automática exige Open Finance (Pluggy em produção) ou leitura de notificações no Android.
