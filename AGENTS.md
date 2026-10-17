# Contexto do projeto — Controle de Despesas Mensais

## Objetivo

Aplicativo pessoal de controle financeiro chamado **Clareza**. A experiência deve ser simples, acolhedora e orientada a decisões: mostrar o que importa no mês, facilitar o registro de gastos e dar contexto sobre orçamento e hábitos.

Referências de produto: Pierre brasileiro (CloudWalk, pierre.finance) e MultiCap. Piere americano é outro produto e não deve ser confundido com Pierre. Usar referências públicas como inspiração, preservando a identidade do Clareza.

## Regra permanente de continuidade

- Antes de alterar o projeto, ler este arquivo; atualizar decisões, estado verificado e pendências durante cada entrega. Pedido explícito do usuário em 16/10/2026.
- Não declarar recursos prontos apenas porque existem botões. Verificar API, persistência e interface.
- Preservar alterações locais anteriores do usuário; não incluir `.codex/`, `.github/` ou outros arquivos indiscriminadamente em commits.

## Android em andamento — 17/10/2026

Pedido atual: entregar APK instalável do Clareza, conectado ao Render, sem servidor no computador. Plano e critérios em `docs/android.md`. A base Capacitor 7 existe, mas ainda não havia APK validado. Preparar JDK 21 separado do Java 25 do backend e Android SDK local; não alterar o Java global. Validar exportação nativa, navegação, segurança e regressões web antes da entrega. Não confundir testes no navegador com execução em um aparelho Android.

Implementado nesta etapa: plugin Java `ClarezaFiles` exporta CSV pelo seletor do Android, sem armazenamento intermediário ou permissão ampla; Voltar fecha formulários; backup e HTTP desativados; ícone/splash próprios; service worker desligado no contêiner nativo; logs do Capacitor desativados. Erros de autenticação da API agora incluem CORS para o Android conseguir distinguir chave inválida de falta de conexão.

Verificado: sete testes backend, seis de navegador e quatro unitários Android aprovados; cenário completo no emulador Android 15/API 35 com APK instalado passou por login, CRUD com H2 descartável, recarga, botão Voltar, cancelamento do seletor e CSV efetivamente gravado/lido. Teste em `tests-android/`, configuração `playwright.android.config.cjs`; exige AVD dedicado `Clareza_API_35`, não telefone pessoal. Requisições do APK são encaminhadas ao backend local por automação; isso não confirma login de produção.

Gradle `assembleDebug testDebugUnitTest lintDebug` e assinatura v1/v2 fazem parte de `npm run apk` em `mobile/`. APK em `mobile/artifacts/Clareza-1.0-debug.apk` (ignorado pelo Git). Ferramentas em `%LOCALAPPDATA%/ClarezaTools` e SDK em `%LOCALAPPDATA%/Android/Sdk`. A assinatura é de depuração, não de Play Store. HTTP local não é habilitado no APK. JavaScript usa AbortController com timer para funcionar também em WebViews sem `AbortSignal.timeout`; regressão coberta nos testes.

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
