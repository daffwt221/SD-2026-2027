# COMINDIR04 — implementação até ao item 7 de 4.1

Esta versão implementa os itens 1–7 da [ficha](https://github.com/cunhaestgv/SD-2026-2027/issues/17).
Os quatro projetos usam Java 11 ou superior e o pacote/tópicos `rep04`.

| Item | Código e comportamento |
|---|---|
| 1 | `HeartbeatMonitor`: classe fornecida na ficha, copiada nos quatro projetos. |
| 2 | `Batch`: cabeçalho e corpo multiline, codificação/descodificação, contagem de linhas e validação dos registos. `Wire`: parâmetros de temporização e remoção de `REG`. |
| 3 | `PartitionLeader`: persistência imediata de cada leitura, acumulação em `pendentes` e publicação periódica de lotes, incluindo vazios. |
| 4 | `PartitionReplica`/`PartitionState`: monitor por partição, aplicação ordenada, duplicados ignorados e recuperação TCP até `ultimoSeq`. |
| 5 | `AlarmProcessor`: lê lotes, mantém os relógios causais e os monitores, e avisa antes de enviar um alarme para um líder suspeito. |
| 6 | `Producer`: subscreve as partições, interpreta apenas o cabeçalho e avisa sobre leituras enviadas para líderes suspeitos, mantendo o envio. |
| 7 | `HeartbeatCheck`: lê os ficheiros e calcula estatísticas dos lotes, suspeitas, deteção, latências e bytes por registo. |

## Como funciona

`Batch` é uma representação de uma mensagem `LOTE`. Por exemplo, um heartbeat vazio pode ser
`LOTE;127.0.0.1;7000;1000;1;0;0`. Num lote com registos, a primeira linha é seguida pelas
linhas produzidas por `SensorRecord.toLine()`. O número final do cabeçalho tem de coincidir com
o número de registos. No método `decode(msg, n)`, o argumento `n` representa N, a dimensão
esperada dos vetores; a contagem de registos é lida separadamente do cabeçalho.

O líder continua a atribuir `seq`, atualizar L/V e gravar o ficheiro assim que recebe um `IN`.
Acrescenta o registo a `pendentes` dentro do mesmo `synchronized(file)`. O heartbeat troca essa
lista por uma nova, lê o último `seq` e incrementa `hb` com o mesmo bloqueio. Esta operação
produz um cabeçalho e um corpo correspondentes ao mesmo instante do estado do líder.

A codificação e a publicação ocorrem fora desse bloqueio, para uma chamada HTTP lenta não
impedir novas leituras. Um único `ScheduledExecutorService` executa `enviarLote()` no arranque
e depois com `scheduleAtFixedRate`. Os heartbeats não incrementam L ou V. Uma publicação
falhada não reinsere os registos: eles continuam persistidos e são recuperáveis por TCP.

Cada réplica mantém um monitor por partição. A receção de um lote atualiza esse monitor e
aplica os seus registos. Se o último `seq` aplicado ainda for inferior ao `ultimoSeq` anunciado,
a réplica pede o intervalo em falta por TCP. Isto também funciona num lote vazio e recupera
um último registo que tenha sido persistido mas nunca publicado. Um lote de incarnação antiga
é ignorado para renovar o detetor, mas os seus registos ainda são analisados e aplicados.

A verificação periódica usa uma thread independente e o bloqueio próprio de `HeartbeatMonitor`.
Não precisa do bloqueio de `PartitionState`; uma recuperação TCP bloqueante não suspende a
verificação do timeout. O produtor e o processador não substituem um líder suspeito.

O processador trata individualmente os registos do lote com as regras da COMINDIR03:
deduplica por partição/seq, não volta a gerar alarmes a partir de `ALARM-*`, atualiza o conhecimento
causal e publica fora do bloqueio dos relógios. A suspeita do destino gera um aviso e a publicação
é tentada na mesma.

O produtor mantém a leitura de comandos na thread principal, uma subscrição por partição e
uma tarefa periódica de verificação. `Batch.decodeHeader()` lê apenas a primeira linha:
o produtor não descodifica `SensorRecord` nem valida o corpo. O evento `RECEBIDO` indica
`cabecalho=true`; o intervalo de sequências é inferido do cabeçalho de um lote contíguo.
Ao receber EOF, o produtor termina a verificação e interrompe as threads auxiliares daemon.

`MonitorLog` é um auxiliar que acrescenta o instante físico e grava o mesmo evento na consola
e no ficheiro. Os ficheiros são relativos à pasta em que cada processo é executado:

- `lider-p<k>.txt`: registos persistidos;
- `hb-p<k>.txt`: `inc;hb;instante;ultimoSeq;n;bytes`, por publicação bem-sucedida;
- `monitor-replica<r>.txt`, `monitor-processador.txt` e `monitor-produtor.txt`: eventos do detetor, receções e recuperações aplicáveis a cada participante.

## Relatório HeartbeatCheck

Na pasta `rep04-replica`, depois de reunir os ficheiros de todos os processos numa pasta:

```powershell
java -cp out rep04.HeartbeatCheck 3 ..\evidencias\pontos6-7
java -cp out rep04.HeartbeatCheck 3 ..\evidencias\pontos6-7 ..\evidencias\pontos6-7\falhas.txt
```

`falhas.txt` é opcional e contém linhas `p<k>;instante`, com o instante real da falha em
milissegundos. Sem esse ficheiro, o programa não inventa tempos de deteção. Os ficheiros
de entrada são lidos sem serem alterados; o relatório é escrito na consola.

As linhas do relatório usam nomes de campos e unidades explícitos:

- `LOTES`: por partição/incarnação, número de lotes, vazios, registos por lote e intervalos
  entre publicações. Um único heartbeat não permite calcular um intervalo: aparece `NA`.
- `SUSPEITA`/`MONITOR`: uma retoma válida da mesma incarnação classifica a suspeita como falsa.
  Uma nova incarnação não transforma a suspeita anterior numa falsa suspeita. Sem qualquer
  heartbeat observado antes da suspeita, a classificação é `INDETERMINADA`.
- `DETECAO`: associa cada anotação de falha às primeiras suspeitas reais comparáveis dos
  participantes, respeitando a incarnação e a falha seguinte. Inclui valores individuais
  e um resumo. Uma observação em falta aparece como `NA`.
- `LATENCIA_REGISTO`/`LATENCIA`: usa a primeira receção de cada seq por participante/partição.
  Duplicados não são contados novamente; uma recuperação TCP não é tratada como receção de
  um lote. Os registos sem receção de lote ficam explicitamente com latência `NA`.
- `LATENCIA_PARTICAO`: agrega as observações dos participantes e mostra quantos registos
  distintos estão cobertos. Quando existe uma observação do corpo, usa esse intervalo
  também para correlacionar a receção de cabeçalhos pelo produtor.
- `BYTES`: média ponderada por registo do payload UTF-8 de lotes não vazios, equivalente
  `REG` e custo incluindo os heartbeats vazios. O equivalente `REG` é calculado a partir
  dos bytes publicados e das linhas do líder, sem adivinhar o comprimento do endereço.
  A cobertura de amostras está indicada; dados insuficientes não são substituídos por zero.

`FALHA_REAL` tem a definição observacional da ficha: não há uma retoma válida da mesma
incarnação no registo disponível. Um ficheiro de monitorização finito não prova que um
processo terminou. As anotações de falha permitem medir falhas provocadas com origem conhecida.

As latências comparam relógios físicos e usam o fuso local de quem executa o verificador.
Todos os processos devem correr na mesma máquina e com o mesmo fuso. A implementação
herdada de `SensorRecord.now()` guarda o instante com precisão de segundos: a latência
calculada inclui até 999 ms de truncagem, além da rede e do agendamento. Valores negativos
geram um aviso, em vez de serem convertidos artificialmente em zero.

## Compilar e executar

É necessária uma API HTTP Kubo com Pub/Sub preparada, por omissão em
`http://127.0.0.1:5001/api/v0`. Todos os processos da ficha devem correr na mesma máquina.

Em cada projeto, a partir da respetiva pasta:

```powershell
javac --release 11 -encoding UTF-8 -d out src/rep04/*.java
```

Arrancar em terminais separados, na ordem réplicas → líderes → processador → produtor:

| Pasta | Comando |
|---|---|
| `rep04-replica` | `java -cp out rep04.PartitionReplica 1 0,1` |
| `rep04-replica` | `java -cp out rep04.PartitionReplica 2 1,2` |
| `rep04-replica` | `java -cp out rep04.PartitionReplica 3 2,0` |
| `rep04-lider` | `java -cp out rep04.PartitionLeader 0 3` |
| `rep04-lider` | `java -cp out rep04.PartitionLeader 1 3` |
| `rep04-lider` | `java -cp out rep04.PartitionLeader 2 3` |
| `rep04-processador` | `java -cp out rep04.AlarmProcessor 3` |
| `rep04-produtor` | `java -cp out rep04.Producer 3` |

As réplicas mantêm N=3 e a interface de argumentos da implementação anterior. O último
argumento opcional da réplica é o porto da API; no líder e no processador pode indicar-se
a URL da API conforme a mensagem de utilização de cada programa.

As propriedades de sistema são passadas antes de `-cp`, em todos os processos relevantes:

```powershell
java -Drep04.periodo=5000 -Drep04.fator=3 -cp out rep04.PartitionLeader 0 3
```

Os valores por omissão são período de 5000 ms, fator 3 e verificação de 1000 ms.

## Validação local

A partir da raiz do repositório, em Windows:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Sprints\E1-COMINDIR04\testes\verificar.ps1
```

A opção de execução aplica-se a essa sessão de PowerShell. O script compila os quatro projetos,
compila o teste com a biblioteca-padrão do JDK e inicia os processos de teste. Os artefactos
ficam numa pasta nova dentro de `testes/out/`. Os processos que o teste inicia são terminados
no fim, incluindo em caso de erro.

A suite cobre o formato dos lotes, duplicados e incarnações, aplicação de registos atrasados,
falha de publicação, recuperação via TCP a partir de lotes vazios, suspeita/reinício/retoma,
alarmes e leituras para destinos suspeitos e encerramento do produtor por EOF.
`PartitionCheck`, `CausalCheck` e `HeartbeatCheck` são executados sobre os ficheiros produzidos.
Uma segunda bateria usa ficheiros sintéticos com resultados numéricos conhecidos para validar
as métricas, UTF-8, falsas suspeitas, reinícios, duplicados e dados em falta.
As evidências anteriores estão em [pontos1-5](evidencias/pontos1-5/VALIDACAO.md) e a validação
dos pontos 6–7 em [pontos6-7](evidencias/pontos6-7/VALIDACAO.md).

Esta suite usa uma API HTTP Pub/Sub simulada, com processos Java e ligações TCP reais.
O IPFS não estava disponível no ambiente de validação. Estes resultados validam a lógica local;
os ensaios e as medições exigidos pela ficha com IPFS real continuam por realizar.

## Continuação da ficha

Faltam os ensaios e a análise dos itens 8–11, com IPFS real e as medições exigidas pela ficha.
As evidências locais desta implementação não substituem esses ensaios.
