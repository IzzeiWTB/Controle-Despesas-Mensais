# Despesas Pessoais

MVP de controle financeiro pessoal inspirado no projeto ControleFinanceiro-JAVA. A proposta combina cadastro/importacao de gastos, visao mensal, limites por categoria e uma camada de IA que pode ser ligada ao n8n, WhatsApp ou Telegram.

## Executar

Requisitos: Java 21 e Maven 3.9+.

```powershell
cd backend
mvn spring-boot:run
```

Abra `frontend/index.html` ou use o Live Server. A API roda em `http://localhost:8080`.

## API inicial

- `GET /api/movimentacoes?inicio=2026-09-01&fim=2026-09-30`
- `POST /api/movimentacoes`
- `DELETE /api/movimentacoes/{id}`
- `POST /api/ia/analisar` com `{ "mensagem": "Posso gastar 80 reais hoje?" }`

## Integracao com IA

O endpoint `/api/ia/analisar` e um placeholder deterministico para o MVP. No n8n, o fluxo recomendado e: webhook do WhatsApp/Telegram -> buscar movimentacoes na API -> modelo de linguagem -> validar JSON -> responder pelo mesmo canal. Nunca envie senhas, tokens bancarios ou credenciais para o modelo.

A conexao bancaria real deve usar Open Finance por um provedor autorizado, com consentimento do usuario e tokens armazenados no backend.
