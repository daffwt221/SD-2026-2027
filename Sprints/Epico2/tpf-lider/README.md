# TPF - Épico 2: Arquivo Distribuído Zero-Knowledge

Projeto de Sistemas Distribuídos desenvolvido continuamente ao longo do Épico 2.

## Líder Discord

Aplicação Java com JDA para integração com o Discord.

## Requisitos

- Java 17 ou superior
- Apache Maven
- Bot Discord com Message Content Intent ativado
- Canal #arquivo
- Papel admin-arquivo

## Configuração

Definir a variável de ambiente DISCORD_TOKEN.

Nunca colocar o token no código ou no GitHub.

## Registar comandos

Na pasta tpf-lider:

mvn compile exec:java "-Dexec.mainClass=tpf.CommandRegistrar"

## Executar o líder

mvn compile exec:java

## Comandos Discord

- /registar
- /estado
- /config
- /recuperar
- /replica-token
- /replica-remover
- /rajada

## Estado atual

Implementado:
- Ligação ao Discord.
- Registo dos sete comandos.
- Validação inicial dos comandos.
- Deteção de mensagens no canal #arquivo.

Por implementar:
- Registo persistente com LSN e fsync.
- Chunks cifrados.
- Comunicação WebSocket.
- Gestão das réplicas.
- Catálogo e recuperação.

## Organização

O código permanece em Sprints/Epico2 ao longo dos sprints.