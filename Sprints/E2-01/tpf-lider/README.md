# TPF Líder — Sprint E2-01

Aplicação Java que utiliza JDA para integrar o líder do sistema de arquivo distribuído com o Discord.

## Requisitos

- Java 17 ou superior
- Apache Maven
- Bot Discord com Message Content Intent ativado
- Servidor Discord com canal `#arquivo` e papel `admin-arquivo`

## Configuração

Definir a variável de ambiente `DISCORD_TOKEN` com o token do bot.

No PowerShell:

```powershell
$env:DISCORD_TOKEN = "TOKEN_DO_BOT"
```

O token não deve ser colocado no código ou enviado para o GitHub.

## Execução

Na pasta `tpf-lider`:

```powershell
mvn compile exec:java
```

## Comandos

- `/registar <texto>`
- `/estado`
- `/config r <n>`
- `/recuperar <de> <ate>`
- `/replica-token`
- `/replica-remover <id>`
- `/rajada <n> <intervaloMs>`

Os comandos `/config`, `/replica-token`, `/replica-remover` e `/rajada` exigem o papel `admin-arquivo`.

Nesta sprint, os comandos apenas validam os argumentos e devolvem respostas provisórias. Não existem operações reais de armazenamento, encriptação ou gestão de réplicas.

As mensagens normais enviadas em `#arquivo` são detetadas pelo bot.
