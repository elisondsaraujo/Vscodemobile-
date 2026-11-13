#!/data/data/com.termux/files/usr/bin/bash
# Roda DENTRO do Termux. Ajuste "ubuntu" se sua distro do proot-distro tiver outro nome.
# Mantém o bind em 127.0.0.1 e a autenticação por senha (padrão do code-server):
# no Android, qualquer app do aparelho consegue falar com 127.0.0.1.
exec proot-distro login ubuntu -- bash -lc 'code-server --bind-addr 127.0.0.1:8080'
