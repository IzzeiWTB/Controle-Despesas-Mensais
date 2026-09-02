# Despesas Pessoais

Projeto de controle financeiro pessoal com duas experiencias separadas: uma demonstracao publica, sem banco de dados, e uma API privada para uso pessoal.

## Teste publico

Abra `frontend/index.html` ou publique a pasta `frontend` na Vercel. O site usa dados ficticios. Gastos adicionados ficam apenas no `localStorage` do navegador do visitante; nenhuma informacao e enviada ao Render ou ao Neon.

## App pessoal instalavel

O frontend tambem funciona como PWA. Abra o endereco da Vercel em um navegador compativel e use `Instalar app` (ou a opcao de instalar do navegador). Depois de instalado, ele pode ser usado como um app e continua funcionando offline.

Os lancamentos ficam somente no dispositivo em que foram cadastrados. Nao existe sincronizacao entre aparelhos, backup automatico ou envio para a internet. Para proteger seus dados, use bloqueio de tela e faca exportacao/backup antes de limpar os dados do navegador.

## API privada no Render

O backend roda via Docker no Render gratuito e usa PostgreSQL no Neon. O plano gratuito pode suspender o servico quando estiver inativo, entao a primeira chamada pode demorar.

Variaveis obrigatorias no Render:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://HOST/neondb?sslmode=require
SPRING_DATASOURCE_USERNAME=neondb_owner
SPRING_DATASOURCE_PASSWORD=senha-do-Neon
SPRING_DATASOURCE_DRIVER=org.postgresql.Driver
APP_API_KEY=uma-chave-longa-e-secreta
```

A API exige `X-API-Key` quando `APP_API_KEY` esta configurada. Guarde essa chave somente no n8n ou em um cliente privado; nunca coloque no frontend publico.

Endpoints:

- `GET /api/movimentacoes?inicio=2026-09-01&fim=2026-09-30`
- `POST /api/movimentacoes`
- `DELETE /api/movimentacoes/{id}`
- `POST /api/ia/analisar` com `{ "mensagem": "Posso gastar 80 reais hoje?" }`

## Desenvolvimento local

Requisitos: Java 21 e Maven 3.9+.

```powershell
cd backend
mvn spring-boot:run
```

Sem `APP_API_KEY`, a API fica liberada apenas para facilitar o desenvolvimento local. Em producao, sempre configure uma chave.

## Integracao com IA

O endpoint de analise e um placeholder deterministico para o MVP. O fluxo recomendado no n8n e: Telegram/WhatsApp -> webhook -> API com `X-API-Key` -> regras financeiras -> modelo de linguagem -> resposta no mesmo canal. Nunca envie senhas bancarias, tokens ou credenciais ao modelo.

A conexao bancaria real deve usar Open Finance por um provedor autorizado, com consentimento do usuario e tokens protegidos no backend.

## App privado real

A pasta `private-app` e a interface para uso pessoal. Ela nao deve ser publicada na Vercel como site publico. Para usar, abra essa pasta por um servidor HTTP local, informe sua `APP_API_KEY` na tela e conecte ao backend do Render. Os dados reais continuam no Neon; a chave fica apenas na sessao do navegador.

Exemplo com Node.js:

```powershell
npx serve private-app
```

Abra o endereco informado pelo comando. A vitrine continua em `frontend`; o app privado real fica em `private-app`.

Para instalar no celular, publique `private-app` como um segundo projeto na Vercel usando `private-app` como Root Directory. Abra o endereco no celular e use o menu do navegador: `Adicionar a tela inicial` no Android ou `Adicionar a Tela de Inicio` no iPhone. O endereco fica protegido pela `APP_API_KEY`; sem ela, seus dados nao sao carregados.
