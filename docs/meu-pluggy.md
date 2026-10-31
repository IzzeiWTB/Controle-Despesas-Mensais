# Bancos reais pelo Meu Pluggy (uso pessoal)

A Pluggy só libera bancos reais em produção com contrato comercial. Para uso pessoal existe o **Meu Pluggy**: gratuito, sem aprovação, até 5 conexões, dados reais pelo Open Finance. Referência: https://docs.pluggy.ai/en/docs/guides/meu-pluggy-personal-use

## Passo a passo

1. **Meu Pluggy** (meu.pluggy.ai): conecte seus bancos. Cada banco é autorizado uma vez no app do banco.
2. **Painel da Pluggy** (dashboard.pluggy.ai), entrando com a **mesma conta** do Meu Pluggy:
   - Em *Customization → Connectors*, se a seleção de conectores estiver personalizada, habilite **MeuPluggy** (aba *Direct Connectors*, grupo *Personal*).
   - Em *Applications → sua aplicação → Demo*, clique em *Connect Account*, escolha **MeuPluggy** (não o conector do banco nem o Sandbox) e entre com a conta do Meu Pluggy. Se pedir CPF e aprovação no banco de novo, o conector escolhido está errado.
   - Cada banco vira um **item**. Copie o **Item ID** de cada um em *Connected Items*.
   - Na mesma aplicação, pegue o **Client ID** e gere o **Client Secret** (aparece uma única vez).
3. **Render** (serviço `controle-despesas-mensais` → *Environment*): crie `PLUGGY_CLIENT_ID` e `PLUGGY_CLIENT_SECRET`. Nunca coloque esses valores no código, em capturas ou em conversas.
4. **Clareza**: *Contas e Cartões → + Conectar banco → Meu Pluggy*. Cole o Item ID, escreva o nome do banco (ex.: "Nubank") e toque em **Vincular e sincronizar**. Repita para cada banco.

## Como a sincronização funciona

- **Primeira sincronização** de um item: lançamentos dos últimos 90 dias; nas seguintes, 35 dias (`pluggy.dias-primeira-sincronizacao` e `pluggy.dias-sincronizacao`). Itens vinculados ficam salvos na tabela `item_pluggy`.
- **Automática**: ao abrir o app, itens sincronizados há mais de 6 horas são atualizados em segundo plano. O Meu Pluggy atualiza os dados dos bancos a cada 24 horas. Também há o botão **Sincronizar todos agora**.
- **API de transações**: usa `GET /v2/transactions` (`dateFrom` + cursor `next`). O `GET /transactions` paginado foi descontinuado pela Pluggy e responde **410 ENDPOINT_DEPRECATED** para chaves novas.
- **Sem duplicação**: cada transação usa o id da Pluggy (`pluggy:<id>`); lançamentos editados por você não são sobrescritos.
- **Cartão**: compras entram como despesa (a Pluggy usa `type` DEBIT/CREDIT; sem ele, no cartão valor positivo é compra). Pagamentos de fatura e transferências entre contas do próprio titular (categoria Pluggy "Same person transfer", quando disponível) são ignorados para não contar o gasto duas vezes.
- **Rótulo da conta**: "Nubank" para conta e "Nubank · cartão 1234" para cartão.
- **Autorização**: vale 1 ano. Quando expirar, a sincronização mostra o erro no item; refaça o passo 2 e vincule o novo Item ID (ele pode mudar). Lançamentos antigos são preservados.
- **Categorias**: histórico, regras e Gemini em lote (só com `GEMINI_API_KEY`).

## A verificar no primeiro uso real

Os testes usam um servidor simulado da Pluggy. Na primeira sincronização real, confira: compras do cartão como despesa, fatura atual e limite dos cartões, saldos, datas e se lançamentos pendentes geram duplicidade quando são confirmados.
