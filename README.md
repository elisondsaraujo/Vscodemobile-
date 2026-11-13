# Code Shell

App Android (Kotlin, WebView) que abre o **code-server** local (VS Code completo) em tela cheia,
com suporte a janela redimensionável do Samsung DeX.

## 1. Servidor (Termux)
1. Instale o code-server dentro da sua distro (proot-distro) e rode `scripts/start-code-server.sh` no Termux.
2. A senha fica em `~/.config/code-server/config.yaml` (dentro da distro). Não use `--auth none`.
3. Android 12+ pode matar processos filhos do Termux: verifique nas Opções do desenvolvedor
   a opção de restrições de processos filhos e a otimização de bateria do Termux.

## 2. App
1. Abra esta pasta no Android Studio (JDK 17) e aguarde o Gradle sincronizar.
2. Build > Build APK(s). Saída: `app/build/outputs/apk/debug/app-debug.apk` (assinado com a chave debug).
3. Instale o APK. Endereço padrão: `http://127.0.0.1:8080`. Se o servidor estiver fora do ar,
   a tela de erro oferece "Tentar novamente" e "Alterar endereço".

## Restrições deliberadas
- `http` só para 127.0.0.1/localhost; endereços remotos exigem `https`.
- Links externos abrem no navegador; downloads pelo WebView não estão implementados.
- Botão Voltar é ignorado com o editor aberto (feche a janela pelo DeX/recentes).
