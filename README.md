# Despesas Pessoais

Projeto de controle financeiro pessoal com duas experiencias separadas: uma demonstracao publica, sem banco de dados, e uma API privada para uso pessoal.

## Teste publico

Abra `frontend/index.html` ou publique a pasta `frontend` na Vercel. O site usa dados ficticios. Gastos adicionados ficam apenas no `localStorage` do navegador do visitante; nenhuma informacao e enviada ao Render ou ao Neon.

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
