# Contexto do projeto — Controle de Despesas Mensais

## Objetivo

Aplicativo pessoal de controle financeiro chamado **Clareza**. A experiência deve ser simples, acolhedora e orientada a decisões: mostrar o que importa no mês, facilitar o registro de gastos e dar contexto sobre orçamento e hábitos.

Referências de produto: Pierre brasileiro (CloudWalk, pierre.finance) e MultiCap. Piere americano é outro produto e não deve ser confundido com Pierre. Usar referências públicas como inspiração, preservando a identidade do Clareza.

## Regra permanente de continuidade

- Antes de alterar o projeto, ler este arquivo; atualizar decisões, estado verificado e pendências durante cada entrega. Pedido explícito do usuário em 16/10/2026.
- Não declarar recursos prontos apenas porque existem botões. Verificar API, persistência e interface.
- Preservar alterações locais anteriores do usuário; não incluir `.codex/`, `.github/` ou outros arquivos indiscriminadamente em commits.

## Correção HTTP 410 nas transações Pluggy — 30/10/2026

Primeiro uso real: os itens Nubank e Itaú falharam com "A Pluggy respondeu HTTP 410 em /transactions". Causa: o `GET /transactions` paginado (`page`/`pageSize`/`from`) foi descontinuado e responde 410 `ENDPOINT_DEPRECATED` para chaves novas, antes da data anunciada (31/12/2026). Corrigido: `PluggyService.consultarTransacoes` usa `GET /v2/transactions?accountId=&dateFrom=yyyy-mm-dd` e segue o cursor opaco `next` (query string pronta; `null` na última página; até 40 páginas). Erros da Pluggy passam a incluir `code`/`message` do corpo (ex.: "HTTP 410 em /transactions (ENDPOINT_DEPRECATED)"). `/investments` continua paginado por `page`. Stub de teste responde 410 na rota v1. 68 testes de backend aprovados. Ainda não confirmado com a API real.

Após o 410: "Erro ao gravar os dados sincronizados" em Nubank e Itaú. Causa reproduzida em teste: a sincronização automática ao abrir o app e o botão "Sincronizar todos" rodavam em paralelo no mesmo item; a segunda violava o UNIQUE de `conta_conectada`/`movimentacao`. Corrigido com trava por item (`ReentrantLock`, espera até 3 min; a segunda encontra tudo importado). Também: a mensagem do item mostra a causa mais específica (primeira linha, sem o "Detail" do PostgreSQL) e o log registra o stack; contas e investimentos com id repetido são ignorados; investimentos com saldo ≤ 0 ignorados; caracteres de controle removidos dos textos (PostgreSQL rejeita `\u0000`); categoria vazia vira "Outros". 71 testes de backend aprovados.

Avaliado e não adotado: "Banco MCP" (banco.mcp.ai): agregador sem operador, CNPJ ou termos de privacidade identificados na página; exige entregar consentimento Open Finance a terceiro desconhecido. Não integrar sem verificar a empresa.

## Entrega Meu Pluggy e correções da sincronização Pluggy — 30/10/2026

Contexto: a Pluggy não aprovou produção para o titular. O caminho adotado é o **Meu Pluggy** (uso pessoal, gratuito, sem due diligence, até 5 conexões, dados reais; atualização a cada 24 h; autorização de 1 ano). O titular já conectou Nubank, Itaú e Santander e três cartões no meu.pluggy.ai. Passo a passo em `docs/meu-pluggy.md`.

Implementado (commit local, sem push nem deploy):
- `ItemPluggy` + migração `V5__item_pluggy.sql`: itens vinculados com apelido, instituição, status, validade da autorização, última sincronização e último erro.
- Endpoints: `GET /api/open-finance/itens`, `POST /api/open-finance/itens` (`{itemId, nome}`; vincula e sincroniza), `POST /api/open-finance/sincronizar-todos?apenasDesatualizados=`. Erros da Pluggy retornam 502 com `{"erro"}` legível; entrada inválida, 422.
- Correções na sincronização real (eram bugs que afetariam dados reais): tipo pela `type` DEBIT/CREDIT (no cartão a Pluggy envia compras com valor positivo; antes viravam ENTRADA); paginação de transações e investimentos (`pageSize=500`; antes só a primeira página); pagamentos de fatura e "Same person transfer" ignorados; nome do banco pelo apelido ou `GET /items/{id}` (o objeto Account não tem `connector`); rótulo "Banco · cartão 1234"; datas UTC com horário convertidas para Brasília (meia-noite UTC mantida como data); `amountInAccountCurrency`; investimentos zerados/resgatados ignorados e tipos mapeados para RENDA_FIXA/ACOES/FUNDOS/OUTROS.
- HTTP e categorização fora da transação; gravação em `TransactionTemplate` com cache de ids e de linhas legadas por dia. Categorização compartilhada em `importacao/CategorizadorLancamentos` (histórico > regras > Gemini em lote), usada também pelo importador OFX/CSV.
- Janela: 90 dias na primeira sincronização do item, 35 nas seguintes.
- PWA: seção "Meu Pluggy" no diálogo Open Finance (Item ID + nome, lista de itens com status, desvincular, sincronizar todos); Pluggy Connect e Sandbox movidos para "Outras formas de conectar"; sincronização automática em segundo plano ao entrar se algum item tiver mais de 6 h.

Verificado: 67 testes de backend (PluggySincronizacaoTest 19, com stub da Pluggy incluindo paginação, cartão, fatura, item e erros) e 9 E2E aprovados; diálogo em 375 px sem overflow. **Não verificado com a API real da Pluggy/Meu Pluggy.**

Publicado em 30/10/2026: `734d30c` na main (inclui `6f307e0`, `fa53902`, `0335123`), por decisão do titular **sem backup prévio do Neon** (migrações apenas aditivas). Render serviu a versão nova em ~150 s: `/app.js` idêntico à fonte, `sw.js` em `v5`, `/` 200, API sem chave 401 com `no-store` e CORS. O app só sobe após Flyway + `validate`, então V2–V5 foram aplicadas no Neon. O titular informou que `PLUGGY_CLIENT_ID`/`PLUGGY_CLIENT_SECRET` estão no Render e conectou 3 itens MeuPluggy no painel (Demo) com dados reais (ex.: Santander e cartão final 4177).

Pendente do titular: vincular os 3 Item IDs no app (informando o nome do banco) e conferir na primeira sincronização sinais do cartão, fatura, datas e eventual duplicidade de lançamentos pendentes. Instalar o APK novo no telefone.

## Entrega Importação de Extratos e Faturas (OFX/CSV) — 30/10/2026

Objetivo: dados reais de bancos e cartões sem depender do contrato de produção da Pluggy. O titular exporta o extrato/fatura no banco e importa no Clareza. Guia de uso e API em `docs/importacao.md`.

Implementado nesta etapa (commit local na branch `appmod/java-upgrade-20260903013431`, sem push nem deploy):
- Pacote `importacao`: `ExtratoParser` (OFX SGML/XML de conta e cartão; CSV com `,`/`;`/tab, preâmbulo, colunas Data/Descrição/Valor ou Débito+Crédito, Identificador; valores e datas em formatos brasileiros; UTF-8 ou Windows-1252), `ImportacaoService` e `ImportacaoController` (`POST /api/importacoes/extrato`, multipart, `confirmar=false` pré-visualiza sem gravar).
- Deduplicação por `id_externo` `arq:<sha256>` (FITID/identificador do banco ou conteúdo + ordem de ocorrência, escopo pela conta). Reimportar não duplica; compras idênticas no mesmo dia são mantidas.
- Status por lançamento: NOVO, JA_IMPORTADO, POSSIVEL_DUPLICADO (mesma data/valor/tipo de outra origem; fora por padrão) e PAGAMENTO_FATURA (fora por padrão para não contar o gasto duas vezes).
- Categorização: histórico do usuário (mesma descrição) > `GeminiService.categorizarHeuristica` > `GeminiService.categorizarLote` (uma chamada para o que sobrar como "Outros", só com `GEMINI_API_KEY`). A chave do Gemini passou da URL para o cabeçalho `x-goog-api-key`.
- Limites: 2 MB (`spring.servlet.multipart`), 5.000 lançamentos, 300 itens na resposta. Erros de arquivo retornam 422 `{"erro"}`; tamanho, 413.
- PWA: botão "Importar extrato" em Movimentações e atalho no diálogo Open Finance; diálogo com pré-visualização, avisos e confirmação. Campo do formulário chama-se `contaImportacao` para não colidir com `[name=conta]` do lançamento. Service worker `v5`.

Verificado:
- 60 testes de backend aprovados (inclui `ExtratoParserTest` 10 e `ImportacaoIntegrationTest` 7).
- 9 testes E2E aprovados, incluindo importação com pré-visualização, confirmação, reimportação sem duplicar e arquivo inválido. Captura em 375px sem overflow horizontal.
- `node --check`, `npm run check` e `git diff --check` sem erros.
- `npm --prefix mobile run apk` (assembleDebug, testes unitários e lint) aprovado; APK `mobile/artifacts/Clareza-1.0-debug.apk` SHA-256 `B3E38F4157EFA68C6912C6D5C6A662C01CE6B4F3793FFE10BC4CC9C91A8752DD`. Seleção de arquivo no APK não foi testada em emulador nem telefone.
- Não testado com arquivos reais de bancos; layouts não reconhecidos exibem erro e exigem ajuste no parser com exemplo anonimizado.
- Pendente para uso real: push para `main` (deploy no Render aplica Flyway V2–V4 no Neon; fazer backup antes) e instalar o novo APK.

## Entrega Arquitetura de Banco de Dados — 30/10/2026

Objetivo: revisar a arquitetura de banco e corrigir integridade, deduplicação e gestão de esquema.

Implementado nesta etapa (commit local `6f307e0` na branch `appmod/java-upgrade-20260903013431`, sem push nem deploy):
- Flyway (`flyway-core` + `flyway-database-postgresql`) com migrações em `backend/src/main/resources/db/migration/`:
  - `V1__esquema_inicial.sql`: baseline igual ao esquema que o Hibernate gerava.
  - `V2__indices.sql`: índices em `movimentacao.data`, `conta_conectada.item_id` e `investimento.item_id`.
  - `V3__ids_externos.sql`: `id_externo` anulável com UNIQUE em `movimentacao`; UNIQUE(`item_id`, `id_externo`) em `conta_conectada` e `investimento`.
  - `V4__recorrencia_origem.sql`: `recorrencia_origem_id` UNIQUE com FK ON DELETE SET NULL.
- `application.properties`: `ddl-auto=${JPA_DDL_AUTO:validate}`, `spring.flyway.baseline-on-migrate=true` e `baseline-version=1`. Bancos existentes recebem baseline e pulam a V1. `JPA_DDL_AUTO=none` é a válvula de escape no Render. Testes e `scripts/test-server.mjs` não usam mais `create-drop`; o esquema dos testes vem do Flyway.
- Sincronização Pluggy idempotente: ids externos `pluggy:<id>` e `sandbox:<itemId>:<n>`; upsert de contas e investimentos por (itemId, idExterno); transação já importada não é reimportada, mesmo se editada; linhas legadas sem id recebem o id em vez de duplicar. URL configurável por `pluggy.api.url`.
- Removido o fallback silencioso que gravava dados fictícios no livro-caixa real quando a Pluggy real falhava. Agora retorna 502 (`PluggyIndisponivelException`); itemId vazio ou inválido retorna 400; o fluxo explícito `mock-sandbox-item` foi preservado; remover item sandbox também apaga seus lançamentos fictícios.
- Recorrentes robustos: vínculo com a origem; duas recorrentes idênticas geram dois clones; clone editado não é recriado; conflito retorna 409.
- `atualizadoEm` renovado em todo update; `@Column(precision = 38, scale = 2)` nos campos monetários; valores do Pluggy arredondados HALF_UP; descrição importada truncada em 100 caracteres.
- `faturaAtual` do cartão passou a usar `balance` (antes lia `balanceCloseDate`, que é uma data). Confirmar contra o JSON real da Pluggy.
- `RelogioConfig` fornece `Clock` em `America/Sao_Paulo` para “hoje” e período padrão (antes dependia do fuso UTC do contêiner).
- PWA: mensagens para respostas 502 e 409 em `private-app/app.js`, sincronizado para `backend/src/main/resources/static/`.

Verificado (local, H2):
- 43 testes de backend aprovados: ApiIntegrationTest 16, ApiKeyFilterTest 1, MigracaoFlywayTest 1, PersistenciaOpenFinanceTest 10, PluggySincronizacaoTest 12 e RelogioTest 3, com stub HTTP do JDK no lugar da Pluggy.
- 8 testes E2E do Playwright aprovados; `node --check private-app/app.js`, `npm run check` e `git diff --check` sem erros.
- Banco legado simulado em H2 em arquivo: baseline na versão 1 e V2–V4 aplicadas com `validate`. Não testado em PostgreSQL.

Pendências antes do deploy (titular):
- Fazer backup ou branch do Neon; comparar `pg_dump --schema-only` com a V1; acompanhar o primeiro boot (baseline 1 e V2–V4 nos logs).
- Auditar dados de produção: valores distintos de `tipo`, duplicatas, lançamentos fictícios do antigo modo simulado (iFood, Uber, Posto, Drogaria, Netflix).
- Validar sincronização Pluggy sandbox real (ids e campo `balance` de cartão).
- Decisões de produto em aberto: tabela de categorias, FK de `conta` para `ContaConectada`, conciliação entre lançamento manual e importado, modo simulado em produção e remoção do `AnaliseController` legado.
- Não feito: tirar chamadas HTTP/Gemini da transação de sincronização.

## Entrega Open Finance e Inteligência Artificial (Google Gemini + Pluggy) — 18/10/2026 a 20/10/2026

Objetivo: capacitar o Clareza com conexão bancária automática via Open Finance (extrato unificado de contas, cartões de crédito e investimentos) e Inteligência Artificial generativa com Google Gemini para categorização preditiva e leitura analítica/acolhedora do mês.

Implementado nesta etapa:
- **Open Finance via Pluggy**:
  - Entidades JPA `ContaConectada` e `Investimento`, repositórios dedicados e serviço `PluggyService`.
  - Endpoints em `OpenFinanceController`: `GET /api/open-finance/contas`, `GET /api/open-finance/investimentos`, `POST /api/open-finance/sincronizar`, `POST /api/open-finance/connect-token` e `GET /api/open-finance/status`.
  - Suporte duplo: API real do Pluggy conectada via credenciais (`PLUGGY_CLIENT_ID` e `PLUGGY_CLIENT_SECRET`) e modo Sandbox simulado quando desconfiguradas, permitindo testes sem credenciais bancárias de produção.
  - Widget oficial **Pluggy Connect v2.5.0** integrado na interface para autenticação visual de bancos.
  - Sincronização automática que extrai contas, faturas e limites de cartão, posições consolidadas de investimentos (renda fixa, ações, índices) e transações dos últimos 30 dias que são categorizadas automaticamente pela IA e salvas nas movimentações do mês.
- **Inteligência Artificial (Google Gemini)**:
  - `GeminiService` e `IaController`: `POST /api/ia/categorizar`, `GET /api/ia/insights?periodo=YYYY-MM` e `GET /api/ia/status`.
  - Modelo `gemini-2.5-flash` via REST nativo (Java 25 `HttpClient` + Jackson, zero dependências externas extras) com ativação por `GEMINI_API_KEY`.
  - Fallback heurístico e analítico inteligente e determinístico quando a chave não estiver configurada ou a rede estiver offline.
  - Botão "✦ Sugerir com IA" no modal de lançamentos da PWA que prevê a categoria com base no histórico e descrição digitada.
  - Botão "✦ Leitura com IA" no card "Clareza em Foco", fornecendo leitura acolhedora e dicas práticas considerando limites e metas.
- **Interface e PWA**:
  - Visão unificada de Contas e Cartões com faturas em aberto e limites de crédito.
  - Visão consolidada de Investimentos com totalização de patrimônio e rentabilidade.
  - Modal de Open Finance aprimorado com dois fluxos claros: `✦ Conectar Banco Real (Pluggy Connect)` e `Simular Contas em Modo Sandbox (Teste)`.
  - Cache do Service Worker avançado para `v4`.
- **Responsividade e Estilos**:
  - Estilos dedicados (`.of-card`, `.of-tag`, `.ai-suggestions`) mantendo o design acolhedor do Clareza.

Verificado:
- Doze testes de backend aprovados (`ApiIntegrationTest` e `ApiKeyFilterTest`).
- Oito testes E2E do Playwright aprovados (`npm run test:e2e`), cobrindo login, CRUD, limites, metas, exportação CSV, responsividade mobile, IA e Open Finance.
- Validação estática: `node --check private-app/app.js`, `npm run check` e `git diff --check` aprovados sem advertências.
- Contêiner Android: `npm --prefix mobile run apk` executou `assembleDebug testDebugUnitTest lintDebug` com sucesso, gerando APK assinado em `mobile/artifacts/Clareza-1.0-debug.apk` (SHA-256 `3873F9D1BADA6DB3DDB844C164EF372921CB5D740FE2D02F519A5FD482AF75CD`).
- Deploy de produção verificado no Render e integrado ao repositório GitHub (`main`).

Limitações do agregador externo e próximos passos:
- **Restrição de produção da Pluggy**: Chaves de desenvolvimento / trial da Pluggy permitem conexões apenas com conectores Sandbox (`Pluggy Bank`). Bancos reais (Itaú, Nubank, etc.) exigem aprovação cadastral e contratação comercial de produção junto à Pluggy.
- **Alternativas diretas em avaliação**: Implementar importador de extratos/faturas com 1 clique via `.OFX` / `.CSV` com categorização do Gemini, e/ou leitor de notificações bancárias no Android (`NotificationListenerService`), oferecendo dados 100% reais sem dependência de agregadores comerciais regulados.

## Entrega Limites, Metas e Recorrência — 18/10/2026

Objetivo: evoluir o Clareza para sincronizar limites por categoria no backend, introduzir metas financeiras (objetivos de economia), permitir importar lançamentos recorrentes do mês anterior e exibir visão por conta.

Implementado nesta etapa:
- **Limites no Backend**: `LimiteCategoria`, `LimiteCategoriaRepository` e `LimiteCategoriaController` (`GET /api/limites`, `PUT /api/limites`). Teto por categoria com `BigDecimal` e validações completas (escala <= 2, precisão <= 12). A PWA sincroniza com a API e mantém fallback local caso offline.
- **Metas Financeiras**: `MetaFinanceira`, `MetaFinanceiraRepository` e `MetaFinanceiraController` (`GET/POST/PUT/DELETE /api/metas`). CRUD completo na interface com barra de progresso visual, porcentagem concluída, valor guardado vs alvo, data limite opcional, edição e exclusão.
- **Importação de Recorrentes**: Endpoint `POST /api/movimentacoes/recorrencias?periodo=YYYY-MM` no backend que localiza itens marcados como recorrentes no mês anterior e os clona no mês selecionado preservando valores, conta e categoria com proteção idempotente contra duplicações. Botão "Trazer recorrentes" na PWA com feedback visual.
- **Visão por Conta**: Agrupamento em tempo real dos lançamentos do mês por conta/cartão, calculando entradas, saídas e saldo por conta.
- **Responsividade Mobile**: Corrigido overflow horizontal no mobile (larguras <= 375px) com `flex-wrap: wrap` nas ações das seções.

Verificado:
- Dez testes de backend aprovados (`ApiIntegrationTest` e `ApiKeyFilterTest`).
- Sete testes E2E do Playwright aprovados (`npm run test:e2e`), cobrindo login, CRUD, filtros, exportação CSV, limites sincronizados, criação/edição/exclusão de metas, repetição de recorrentes e responsividade mobile sem scroll horizontal.
- Validação estática: `node --check private-app/app.js`, `npm run check` e `git diff --check` aprovados sem advertências.
- Contêiner Android: `npm --prefix mobile run apk` executou `assembleDebug testDebugUnitTest lintDebug` com sucesso, gerando APK assinado em `mobile/artifacts/Clareza-1.0-debug.apk` (SHA-256 `3F99176C9817A50FC559D7591305530310A7B302A9245CDD46998D1E7E87E437`).

Pendências atuais:
- Investimentos e Open Finance foram implementados na entrega seguinte (Pluggy).
- As contas bancárias continuam sendo identificações nos lançamentos do mês, sem conciliação bancária externa automática.

## Entrega Android — 17/10/2026

Pedido atendido nesta etapa: APK de teste instalável do Clareza, configurado para o Render, sem servidor no computador. Plano, instalação e critérios em `docs/android.md`. JDK 21 e SDK Android preparados separadamente do Java 25 do backend, sem alterar o Java global. Não confundir testes no emulador com execução no telefone do titular.

Implementado nesta etapa: plugin Java `ClarezaFiles` exporta CSV pelo seletor do Android, sem armazenamento intermediário ou permissão ampla; Voltar fecha formulários; backup e HTTP desativados; ícone/splash próprios; service worker desligado no contêiner nativo; logs do Capacitor desativados. Erros de autenticação da API agora incluem CORS para o Android conseguir distinguir chave inválida de falta de conexão.

Verificado: sete testes backend, seis de navegador e quatro unitários Android aprovados; cenário completo no emulador Android 15/API 35 com APK instalado passou por login, CRUD com H2 descartável, recarga, botão Voltar, cancelamento do seletor e CSV efetivamente gravado/lido. Teste em `tests-android/`, configuração `playwright.android.config.cjs`; exige AVD dedicado `Clareza_API_35`, não telefone pessoal. Requisições do APK são encaminhadas ao backend local por automação; isso não confirma login de produção.

Gradle `assembleDebug testDebugUnitTest lintDebug` e assinatura v1/v2 fazem parte de `npm run apk` em `mobile/`. APK em `mobile/artifacts/Clareza-1.0-debug.apk` (ignorado pelo Git). Ferramentas em `%LOCALAPPDATA%/ClarezaTools` e SDK em `%LOCALAPPDATA%/Android/Sdk`. A assinatura é de depuração, não de Play Store. HTTP local não é habilitado no APK. JavaScript usa AbortController com timer para funcionar também em WebViews sem `AbortSignal.timeout`; regressão coberta nos testes.

Código funcional publicado na main em `9225c63`. Render verificado em 17/10/2026 após deploy: `/app.js` retorna 200 e corresponde exatamente à fonte; API sem chave retorna 401 com `Access-Control-Allow-Origin: *` para `Origin: https://localhost` e `Cache-Control: no-store`. APK final SHA-256 `74D96150010D337DB3AC1FABAAEB4B387CEF1081DDA35B0F152CD54E0D1C5EA8`, aproximadamente 4 MB. Lint final: zero erros e 18 avisos herdados de dependências/assets. Emulador e servidores de teste encerrados após validação.

Próximo passo do titular: transferir/instalar APK no telefone, rotacionar credenciais expostas e entrar com a nova `APP_API_KEY`. Não houve login em produção nem teste em telefone físico. Não há publicação em loja, assinatura de produção ou atualização automática do APK.

## Entrega web — 16/10/2026

Objetivo: acessar a PWA pelo próprio endereço do Render, entrar com a chave e operar lançamentos reais, sem servidor separado no computador. Plano e critérios em `docs/funcionalidade.md`.

Problemas corrigidos: preflight CORS, respostas 400/404, validação de tipo/valor, proteção sem chave configurada, interface na raiz, edição no celular e limpeza de valores obsoletos quando falha a atualização. Recorrência agora é identificada honestamente como marcação, sem automação.

Validação local: integração real com H2 em memória e navegador Chrome headless. Testes em `backend/src/test` e `tests/`. Fluxo completo de login, CRUD, recarga, filtros, limites, privacidade e CSV verificado; falha de rede e ausência de cache da API cobertas.

Deploy: `node scripts/sync-app.mjs` gera cópias de `private-app/` em `backend/src/main/resources/static/` (versionada) e `mobile/www/` (ignorada). Não editar essas cópias diretamente. A versão hospedada resolve `/api` na própria origem; a versão Android aponta para o Render. Preservar Root Directory `backend` no Render.

Produção verificada em 16/10/2026, 20h52 (America/Sao_Paulo): commit funcional `43f85dd` publicado na main e no Render. `/` retorna 200 e mostra login; `/app.js` é idêntico ao arquivo testado; API sem chave retorna 401 com `no-store`; preflight OPTIONS retorna 200. Navegador Chrome abriu a tela pública em largura de celular sem erros JavaScript. Total local: sete testes de backend e quatro de navegador aprovados.

Pendente: login com a chave de produção nova deve ser feito pelo titular na interface. Não reutilizar credenciais expostas. Limites continuam locais; contas são rótulos, recorrência é marcação; metas, investimentos e Open Finance não existem ainda.

Git: há mais de uma conta no Git Credential Manager deste computador. Para este repositório, usar `git -c credential.username=IzzeiWTB push origin HEAD:main` evita o seletor de conta. Não imprimir credenciais. Alterações apenas documentais podem usar `[skip render]` no commit para não disparar outro build.

Credenciais expostas em captura anterior precisam ser rotacionadas pelo titular no Render/Neon. Não há confirmação dessa rotação; não reutilizar os valores da captura em testes.

## Estrutura

| Diretório | Papel |
| --- | --- |
| `backend/` | API Spring Boot com PostgreSQL no Render e H2 no desenvolvimento local. |
| `private-app/` | Produto principal: PWA privada que usa a API autenticada. |
| `frontend/` | Demonstração pública com dados fictícios e `localStorage`. Não conecta dados reais. |
| `mobile/` | Contêiner Android Capacitor para `private-app`. |

## Arquitetura e segurança

- A API pública de produção é `https://controle-despesas-mensais.onrender.com/api`.
- A chave `APP_API_KEY` é segredo: nunca registrar, exibir, inserir no código, no README ou em screenshots.
- Se uma chave ou senha aparecer em captura de tela, terminal, commit ou conversa, trate-a como comprometida e instrua a rotação imediata no Render/Neon.
- O app privado guarda a chave somente em `sessionStorage`; não migrar para `localStorage`.
- API sem `APP_API_KEY` configurada retorna 503, inclusive no desenvolvimento. Nunca liberar dados quando o segredo está ausente.
- Service worker só armazena arquivos da interface. Respostas financeiras usam `Cache-Control: no-store`.
- `frontend/` deve permanecer uma demo sem chamadas autenticadas à API de produção.
- Não anunciar integração Open Finance, IA externa, criptografia ou sincronização de dados que não esteja realmente implementada.

## Desenvolvimento

### Backend local

Requer Java 25 e Maven 3.9+.

```powershell
cd backend
$env:APP_API_KEY="uma-chave-local"
mvn spring-boot:run
```

Sem variáveis de banco, a API usa H2 em `backend/data/`.

Para testes: `mvn -f backend/pom.xml clean package`, `npm ci`, `npm run sync`, `npm run check`, `npm run test:e2e`. O teste de navegador usa a porta 18765, chave fictícia e banco em memória, encerrados no final. Não usar Neon ou credenciais reais em testes.

### App privado

```powershell
npx serve private-app
```

Para uso local, configurar a URL `http://localhost:8080/api`; para produção, usar a URL do Render. O app deve ser servido via HTTP/HTTPS para PWA e service worker funcionarem.

### Android

Após alterar `private-app/`, sincronizar antes de abrir/gerar o Android:

```powershell
cd mobile
npm run apk
```

`npm run build` apenas sincroniza o Capacitor; `npm run apk` compila, testa e entrega o instalador no Windows. Consulte `docs/android.md` para instalar no celular e reproduzir os testes.

## Convenções de produto

- Priorizar fluxo de caixa mensal, categorias, recorrências, limites, metas e privacidade.
- Persistir no backend somente recursos que precisam ser compartilhados entre dispositivos. Preferências visuais e limites locais podem ficar no dispositivo, desde que a interface deixe isso claro.
- Tratar valores financeiros como `BigDecimal` no backend e números com duas casas no frontend.
- Datas devem usar `LocalDate` no backend e ser apresentadas em `pt-BR` no frontend.
- Manter interfaces responsivas, acessíveis, em português brasileiro e sem dependência de frameworks quando não forem necessários.

## Antes de publicar

1. Validar JavaScript com `node --check private-app/app.js`.
2. Executar `git diff --check`.
3. Executar os testes Maven quando o Maven estiver disponível.
4. Sincronizar `mobile/` se o app privado mudou.
5. Sincronizar os arquivos da PWA para o backend e verificar que `/` serve a tela de acesso. Hospedagem conjunta simplifica acesso e evita CORS em produção.
