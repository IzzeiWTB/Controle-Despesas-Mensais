# Entrega funcional do Clareza

## Resultado esperado

Abrir a URL do Render, autenticar e consultar/criar/editar/excluir lançamentos por mês. O app deve informar claramente carregamento, falhas e ausência de conexão, proteger credenciais e manter acesso às ações no celular.

## Ordem de implementação

1. Testes de integração para acesso autenticado, preflight, validação, CRUD e período.
2. API: corrigir preflight, respostas 400/404, validação financeira e cache privado.
3. PWA: estados de conexão, prevenção de envio duplicado, datas locais, filtro sem dados obsoletos, exportação CSV segura e edição móvel.
4. Recorrência: tratar a marcação como identificação de gasto recorrente, deixando explícito que não gera cobranças ou lançamentos automaticamente. Automação fica pendente até existir um modelo de séries e exceções persistentes.
5. Empacotar a interface na pasta estática do Spring por script reproduzível, preservando o contexto Docker `backend` usado no Render.
6. Executar testes com banco isolado, verificar interface no navegador, sincronizar Android, atualizar documentação e publicar na main.

## Critérios de aceite

- GET `/` serve a interface; nenhum segredo está nos arquivos estáticos.
- API retorna 401 sem chave e permite OPTIONS sem exigir segredo.
- CRUD persiste e filtra por mês; valores inválidos são rejeitados; IDs inexistentes retornam 404.
- Falha de login ou de recarga tem mensagem visível; múltiplos cliques não duplicam POST.
- Modo privacidade esconde valores e indicadores; exportação avisa que contém dados reais.
- Service worker guarda somente arquivos estáticos da própria aplicação, nunca respostas da API.
- Render só é considerado verificado após resposta pública; login real depende de chave válida inserida pelo usuário na interface.

## Fora desta entrega

Open Finance, IA externa, metas/investimentos, automação de séries recorrentes e autenticação multiusuário. O app continua sendo um controle pessoal de um único proprietário.
