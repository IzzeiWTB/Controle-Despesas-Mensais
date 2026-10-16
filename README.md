# Clareza — Controle de Despesas Mensais

Controle financeiro pessoal com API Spring Boot, PostgreSQL no Neon e interface instalável. A aplicação é de um único proprietário: quem conhece a chave de acesso tem acesso a todos os lançamentos.

## Usar no Render

Após o deploy desta versão, abra **https://controle-despesas-mensais.onrender.com/** e informe sua `APP_API_KEY` no campo Chave de acesso. A interface usa a API do mesmo endereço; não é necessário executar Java, Node ou Maven no computador para usar o app hospedado.

O primeiro acesso pode demorar enquanto o serviço inicia. A interface informa carregamento, erro de acesso e falhas de conexão. A tela é pública, mas os dados exigem a chave.

Não compartilhe capturas de tela com valores de variáveis de ambiente. Se uma credencial foi exposta, troque a chave no Render e a senha do banco no Neon; atualize no Render tanto `SPRING_DATASOURCE_PASSWORD` quanto qualquer senha incorporada à URL JDBC. Aguarde o redeploy e use a chave nova na tela de acesso. Não registre segredos no Git.

## Recursos implementados

- Consulta por mês, entradas, despesas e saldo dos lançamentos registrados (não é o saldo bancário real).
- Inclusão, edição, exclusão e busca por descrição, categoria e conta.
- Campo de conta/cartão como identificação manual, sem integração bancária.
- Marcação de recorrência: identifica registros recorrentes, **sem criar automaticamente os próximos meses**.
- Limites por categoria no navegador, separados pelo endereço da API e aplicados a todos os meses; não sincronizam entre dispositivos.
- Resumo calculado por regras, modo privacidade e exportação CSV do mês.
- Interface responsiva e PWA. Precisa de internet para consultar e salvar dados; o cache contém somente a interface.

## Estrutura e publicação

- `private-app/`: fonte da interface principal.
- `backend/`: API e cópia gerada da interface em `src/main/resources/static/`.
- `frontend/`: demonstração independente com dados fictícios locais. Não usa a API privada.
- `mobile/`: projeto Android Capacitor.
- `AGENTS.md`: contexto, regras permanentes e estado verificado do projeto.

Depois de alterar a interface:

```powershell
npm run sync
npm run check
```

Versione a cópia gerada em `backend/src/main/resources/static/` junto com a fonte. Isso preserva a configuração existente do Render: Root Directory `backend`, Dockerfile `Dockerfile`, branch `main`. Não é preciso criar outro serviço de hospedagem. `npm run check` detecta cópias desatualizadas.

Variáveis de produção (preencher diretamente no Render):

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://HOST/neondb?sslmode=require
SPRING_DATASOURCE_USERNAME=usuario-do-banco
SPRING_DATASOURCE_PASSWORD=<senha>
SPRING_DATASOURCE_DRIVER=org.postgresql.Driver
APP_API_KEY=<chave-longa-aleatoria>
```

Sem `APP_API_KEY`, a API recusa operações com HTTP 503. Sem chave válida na requisição, retorna 401. O app mantém a chave somente na sessão do navegador.

## Desenvolvimento e testes

Requisitos: Java 25, Maven 3.9+ e Node.js. Nesta máquina, o Java foi encontrado em `C:/Program Files/Java/jdk-25.0.4`; o Maven preparado durante o desenvolvimento fica em `%LOCALAPPDATA%/ClarezaTools/apache-maven-3.9.11`.

```powershell
npm ci
npm run sync
$env:APP_API_KEY="chave-apenas-para-desenvolvimento"
cd backend
mvn spring-boot:run
```

Abra `http://localhost:8080`. Sem configuração externa de banco, usa H2 em `backend/data/`. Não utilize dados pessoais nos testes.

```powershell
# A partir da raiz
mvn -f backend/pom.xml clean package
npm run check
npm run test:e2e
```

Os testes de integração usam H2 em memória. Os testes de navegador abrem Chrome headless e iniciam uma instância própria na porta 18765, com banco em memória e chave fictícia. Requerem Google Chrome instalado e o JAR gerado pelo Maven. Não acessam o banco de produção. A instância é encerrada ao terminar.

Como alternativa para desenvolver somente a interface, execute `npx serve private-app`, abra Configurações de conexão e informe a URL da API. APIs remotas exigem HTTPS; HTTP é aceito apenas para localhost.

## Android e PWA

O `npm run sync` da raiz copia a interface para `mobile/www`. Para gerar/abrir o projeto Android:

```powershell
cd mobile
npm ci
npm run open:android
```

Requer Android Studio e SDK Android. O app mobile usa a URL do Render por padrão. Para instalar como PWA, abra o endereço do Render no navegador do celular e use a opção de instalar/adicionar à tela inicial quando disponível.

## API

Todas as operações usam o cabeçalho `X-API-Key`:

- `GET /api/movimentacoes?inicio=2026-10-01&fim=2026-10-31`
- `POST /api/movimentacoes`
- `PUT /api/movimentacoes/{id}`
- `DELETE /api/movimentacoes/{id}`
- `POST /api/ia/analisar`: endpoint legado com regras determinísticas, sem modelo de IA externo.

Dados de lançamento: `descricao`, `valor` positivo com até duas casas, `data` (YYYY-MM-DD), `categoria`, `tipo` (`ENTRADA` ou `SAIDA`), `conta` opcional e `recorrente` booleano. API não guarda resultados em cache HTTP; registros inexistentes retornam 404 e entradas inválidas retornam 400.

## Pendências de produto

Automação de recorrências, orçamento sincronizado, contas com saldos reais, metas, investimentos, login por usuário e Open Finance ainda não foram implementados. A integração bancária requer um provedor, consentimento e proteção de tokens no backend.
