# Sprint E2-02 — Registo durável, chunks e cifra no líder

Entrega exclusiva da [Issue #21](https://github.com/cunhaestgv/SD-2026-2027/issues/21). A implementação é **local ao líder**; réplicas, placement, catálogo, manifesto e recuperação ficam fora deste sprint.

## Requisitos

Java 17+, Maven, Discord com Message Content Intent ativo, canal `#arquivo`, papel `admin-arquivo` e variável de ambiente `DISCORD_TOKEN` definida **localmente**. Nunca publicar tokens nem `master.key`.

Na pasta `Sprints/Epico2/tpf-lider`:

```powershell
mvn clean compile
mvn exec:java "-Dexec.mainClass=tpf.CommandRegistrar"  # registar os 7 comandos no Discord
mvn exec:java "-Dexec.mainClass=tpf.LeaderBot"          # iniciar o líder
```

Comandos funcionais neste sprint: `/registar <texto>` e `/rajada <n> <intervaloms>` (rajada só para `admin-arquivo`, permite `intervaloms=0`). Mensagens humanas em `#arquivo` também são registadas. `/estado`, `/config`, `/recuperar`, `/replica-token` e `/replica-remover` conservam as respostas provisórias de E2-01.

## Parâmetros

A classe `Wire` define os valores padrão `CHUNK_MAX_BYTES=65536`, `PAD_BYTES=4096`, `SELAGEM_MS=5000`, configuráveis na JVM com `-Dtpf.chunk.max.bytes=...`, `-Dtpf.pad.bytes=...` e `-Dtpf.selagem.ms=...`. A cifragem usa a chave mestra de 32 bytes em `master.key`, criada uma vez com `SecureRandom`. A chave tem permissões só para o proprietário; um ficheiro existente com tamanho diferente de 32 bytes impede o arranque.

## Caminho da escrita

1. `/registar`, `/rajada` e mensagens em `#arquivo` usam `RegistoLocal.acrescentar`, um método sincronizado.
2. Atribui-se um LSN estritamente crescente e grava-se `registos.log` via `FileChannel.force(true)` **antes da resposta Discord**.
3. O selador junta os registos duráveis ainda não selados, respeitando o limite de bytes, e fecha o chunk por tamanho ou após 5 s do primeiro registo pendente. Uma chamada após cada registo verifica o limite; um `ScheduledExecutorService` verifica o temporizador.
4. `Chunk` serializa os registos, `ChunkCrypto` aplica padding aleatório e cifra com AES-256-GCM. A leitura inversa decifra, remove padding e reconstrói os cinco campos.
5. O líder publica o `.bin` e depois o `.hdr` em `selados/`, e escreve os eventos `REGISTO` e `SELADO` em `eventos-lider.txt`.

## Formatos exatos

**`registos.log`**: uma linha por registo, UTF-8:

`lsn;instante;autor;canal;textoBase64\n`

`instante` é ISO-8601 UTC; o texto é Base64 de UTF-8 para preservar caracteres especiais. `Chunk.serializar` guarda a concatenação UTF-8 dessas linhas. Ao abrir, `Chunk.ler` analisa as linhas e reconstrói objetos `Registo`.

**Plaintext antes de AES-GCM**: 4 bytes big-endian com o tamanho da serialização, seguidos dos bytes UTF-8 e de bytes aleatórios até múltiplo de `PAD_BYTES`. A leitura usa o prefixo de tamanho para ignorar padding.

**Cifra**: MK de 32 bytes; por chunk, `salt` de 32 bytes, `nonce` de 12 bytes; `DEK = HKDF-SHA256(MK, salt, info="tpf-chunk-v1")` com extração/expansão de um bloco; `AAD = "tpf-v1" || int32(keyVersion=1) || salt || nonce`; AES-GCM com tag de 128 bits. `chunkId` é SHA-256 hexadecimal dos bytes cifrados **incluindo a tag**. `open()` verifica hash, versão e GCM por esta ordem.

**`selados/<chunkId>.bin`**: ciphertext AES-GCM seguido da tag (16 bytes).

**`selados/<chunkId>.hdr`**: Java Properties em UTF-8/ISO-8859-1-compatible, contendo `keyVersion=1`, `salt=<Base64>`, `nonce=<Base64>`, e ainda `lsnDe`, `lsnAte`, `tamanhoPlaintext` para auditar intervalos. O formato é textual `chave=valor` (não usa ponto-e-vírgula); o `salt` e o `nonce` são Base64.

**`eventos-lider.txt`**: `instante;REGISTO;lsn=...;autor=...;canal=...` ou `instante;SELADO;chunkId=...;lsnDe=...;lsnAte=...;tamanho=...`.

## Testes sem Discord

```powershell
mvn clean compile
javac -cp target/classes -d target/classes src/test/manual/tpf/CryptoCheck.java src/test/manual/tpf/SeladorCheck.java src/test/manual/tpf/E202Check.java
java -cp target/classes tpf.CryptoCheck
java -cp target/classes tpf.SeladorCheck
java -cp target/classes tpf.E202Check
```

`E202Check` cria dados **temporários**, verifica selagem por tempo, 500 registos, vários chunks por tamanho, continuidade LSN, eventos, hash e leitura inversa. Com os processos reais, testar no Discord `/rajada 500 0`; verificar eventos, ficheiros em `selados/` e não divulgação do texto na cifra. Não entregar `master.key`, logs ou chunks reais no Git.
