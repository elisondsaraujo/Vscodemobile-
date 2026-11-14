# Code Shell

App Android (Kotlin, WebView) que abre o **code-server** local (VS Code completo) em tela cheia,
com janela redimensionável (Samsung DeX).

## 1. Servidor (Termux)
1. Instale o code-server na sua distro do proot-distro.
2. Copie `scripts/start-code-server.sh` para a home do Termux como `~/start-code-server.sh`
   e rode `chmod +x ~/start-code-server.sh`. Ajuste o nome da distro dentro do script, se não for `ubuntu`.
3. Para o botão "Iniciar servidor" do app funcionar:
   - adicione `allow-external-apps=true` em `~/.termux/termux.properties` e reinicie o Termux;
   - aceite a permissão RUN_COMMAND quando o app pedir.
4. A senha fica em `~/.config/code-server/config.yaml` (dentro da distro). Não use `--auth none`.
5. Android 12+ pode matar processos filhos do Termux: verifique as Opções do desenvolvedor e a
   otimização de bateria do Termux.

## 2. App
1. Abra esta pasta no Android Studio (JDK 17) e aguarde o Gradle sincronizar.
2. Build > Build APK(s). Saída: `app/build/outputs/apk/debug/app-debug.apk` (chave debug).
3. Endereço padrão: `http://127.0.0.1:8080`. Se o servidor estiver fora do ar, a tela de erro oferece
   "Iniciar servidor (Termux)", "Tentar novamente" e "Alterar endereço".

## Comportamento
- `http` só para 127.0.0.1/localhost; endereços remotos exigem `https`.
- Links externos abrem no navegador.
- Downloads: o app pede onde salvar (seletor do sistema) e baixa com o cookie da sessão.
  Não funcionam downloads `blob:`/`data:` nem de outros hosts.
- Voltar: duas pressões em 2 s para sair.
- Se o renderer do WebView for encerrado pelo sistema, o app recria a tela em vez de fechar.
