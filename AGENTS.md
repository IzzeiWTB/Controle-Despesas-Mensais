# Contexto do projeto — Controle de Despesas Mensais

## Objetivo

Aplicativo pessoal de controle financeiro chamado **Clareza**. A experiência deve ser simples, acolhedora e orientada a decisões: mostrar o que importa no mês, facilitar o registro de gastos e dar contexto sobre orçamento e hábitos.

Referências de produto: Piere/Pierre e MultiCap, somente como inspiração de princípios (clareza, orçamento, recorrência, privacidade e visão consolidada). Nunca copiar marca, textos, identidade visual, telas ou código desses produtos.

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

### App privado

```powershell
npx serve private-app
```

Para uso local, configurar a URL `http://localhost:8080/api`; para produção, usar a URL do Render. O app deve ser servido via HTTP/HTTPS para PWA e service worker funcionarem.

### Android

Após alterar `private-app/`, sincronizar antes de abrir/gerar o Android:

```powershell
cd mobile
npm run sync-web
npm run build
```

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
5. Fazer deploy do backend e da interface privada como serviços separados quando houver uma hospedagem permanente para a PWA.
