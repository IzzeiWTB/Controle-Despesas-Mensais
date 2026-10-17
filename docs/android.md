# Android — Clareza

## Escopo e critérios

Entregar APK de teste instalável que empacota a interface, usa a API HTTPS do Render e mantém autenticação em sessão. Consultar/salvar lançamentos exige internet; não prometer operações financeiras offline. Nenhuma chave de produção deve entrar no APK.

- Login, CRUD, filtros, limites locais e privacidade continuam funcionando.
- Exportar CSV abre o seletor de destino do Android, sem permissão ampla de armazenamento. Cancelamento não deve ser apresentado como sucesso.
- Voltar fecha diálogos antes de enviar o app ao segundo plano; não interrompe salvamento em curso.
- Desativar backup dos dados privados e tráfego HTTP; interface empacotada não usa service worker.
- Gerar APK assinado de depuração, verificar assinatura, manifesto e assets. Não é uma publicação na Play Store.
- Distinguir verificações automatizadas de testes em aparelho/emulador e registrar limites da validação.

## Plano

1. Preparar JDK 21 e SDK Android, mantendo Java 25 do backend.
2. Adicionar testes das integrações Android na interface antes da implementação.
3. Implementar exportação pelo Storage Access Framework, navegação e identidade visual nativa.
4. Sincronizar assets, compilar, executar testes e lint, verificar APK.
5. Entregar arquivo e instruções; atualizar contexto e pendências verificadas.

## Segurança

A chave fica somente na sessão. Ao encerrar o processo pode ser necessário entrar novamente. Limites e preferências ficam neste dispositivo. Não restaurar credenciais de capturas antigas: rotacionar no Render/Neon antes do uso real.

## Compilar novamente no Windows

Ferramentas preparadas neste computador:

- JDK 21 em `%LOCALAPPDATA%/ClarezaTools/jdk-21.0.12.1+1` (download oficial Adoptium, SHA-256 conferido).
- Android SDK em `%LOCALAPPDATA%/Android/Sdk`: plataforma 35, build-tools 34/35, platform-tools e emulador.
- Java 25 permanece separado para o backend. O script Android altera variáveis apenas no próprio processo.

```powershell
cd mobile
npm ci
npm run apk
```

Isso sincroniza os arquivos públicos, compila, executa testes unitários e lint, verifica a assinatura e copia o APK para `mobile/artifacts/Clareza-1.0-debug.apk`. Em outro computador, instale JDK 21/SDK Android e configure `CLAREZA_JAVA_HOME` e `ANDROID_HOME`. `npm run build` apenas sincroniza o Capacitor; `npm run apk` gera o instalador.

## Instalar no celular

1. Copie `mobile/artifacts/Clareza-1.0-debug.apk` para o Android por USB ou compartilhamento de arquivo.
2. Abra o arquivo no celular. Se solicitado, permita que esse gerenciador instale apps desta fonte. Após instalar, você pode revogar essa permissão.
3. Abra **Clareza Financeiro**. O endereço do Render já está configurado.
4. Entre com a chave **nova**, rotacionada no Render. Não envie a chave em mensagens ou screenshots.
5. Se o serviço estiver adormecido, aguarde a conexão inicial. Depois crie um lançamento, atualize a lista e confirme o valor.

Requer Android 6+ com Android System WebView atualizado; a validação em emulador não garante todos os modelos/versões. Não requer Java instalado no celular. Conexão HTTPS é obrigatória; HTTP local não é habilitado no APK.

O APK usa assinatura de depuração e é para teste pessoal. Publicação na Play Store exige assinatura de produção, política de privacidade e revisão dos requisitos atuais da loja. Não há atualização automática do APK: alterações da interface nativa exigem gerar e instalar uma versão nova. Alterações da API chegam pelo Render.

## Testes no Android

O teste `tests-android/app.spec.cjs` só aceita emulador e limpa apenas o app Clareza desse emulador. Não executar em telefone pessoal. Ele instala o APK e encaminha requisições para H2 descartável via automação, sem tocar no Neon. O seletor de arquivos e o plugin Java são reais, não simulados.

Com o emulador `Clareza_API_35` iniciado e o backend empacotado:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-25.0.4'
npx playwright test --config=playwright.android.config.cjs
```

## Resultado verificado — 17/10/2026

- APK instalado no emulador Android 15/API 35: chave inválida, login, cadastro/edição/exclusão, recarga com persistência H2, Voltar, cancelamento e gravação real do CSV aprovados.
- Sete testes backend, seis testes no Chrome e quatro testes unitários Android aprovados. Lint Android: zero erros; 18 avisos sobre dependências e recursos herdados do template, sem ocultar regras.
- APK: aproximadamente 4 MB, assinatura de teste v1/v2 válida, `app.js` empacotado idêntico à fonte. SHA-256: `74D96150010D337DB3AC1FABAAEB4B387CEF1081DDA35B0F152CD54E0D1C5EA8`.
- Nenhum telefone físico conectado. Login com chave nova e dados reais permanece para o titular; credenciais antigas expostas não foram utilizadas.
