# COMINDIR05 — executar e estudar

Implementação da [ficha](https://github.com/cunhaestgv/SD-2026-2027/issues/18), baseada na COMINDIR04 deste repositório. RaftState é a classe fornecida pelo docente. A integração, ensaios e documentação foram preparados com apoio de IA.

- [Explicação](EXPLICACAO.md): começa aqui para estudar.
- [Relatório](RELATORIO.md): medições e respostas à reflexão.
- [Evidências](evidencias/execucao-kubo039): ficheiros reais e relatórios.
- [Ensaios](testes/RaftExperiments.java) e [falhas deliberadas](testes/FaultExperiments.java).

## Preparação

Na raiz do repositório, em PowerShell, com java e javac no PATH:

~~~powershell
$sdSprint = Join-Path (Get-Location) 'Sprints/E1-COMINDIR05'
powershell -NoProfile -ExecutionPolicy Bypass -File "$sdSprint/compilar.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -File "$sdSprint/preparar-ipfs.ps1"
~~~

Compila para Java 11; a campanha usou JDK 25.0.2. Fontes UTF-8. Out/, .class e resultados/ estão excluídos do Git.

O segundo script instala Kubo **0.39.0**, verifica o SHA256 oficial e cria um repositório privado em resultados/ipfs-demo. A API é local, 127.0.0.1:5001, sem bootstrap ou descoberta externa. Não altera o repositório IPFS pessoal. Se já existir API 0.39.0, reutiliza-a sem a reconfigurar.

Usa-se PubSub Kubo real. A versão foi fixada após um erro de transporte na 0.43.1, [documentado aqui](evidencias/execucao-20261111/NOTA.md), sem acrescentar reenvios.

## Demonstração

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$sdSprint/iniciar.ps1"
~~~

Arranca os nove nós, o processador e o produtor na mesma pasta. Aguarda cerca de 15 s pelos líderes e escreve no produtor:

~~~text
S02 20.1
S05 23.7
rajada 300 10
sair
~~~

A rajada envia 300 leituras por 10 sensores; o segundo argumento não é um intervalo em ms. S02 e S05 pertencem a p1.

Os nós ficam em segundo plano, com consolas em consola-*. O produtor fica no terminal. Sair termina os processos do script. Após fecho abrupto do terminal, usar parar-sessao.ps1 -Sessao <pasta>: verifica PID, nome e instante de arranque.

Para isolar p1/n2, noutro terminal dentro da pasta da sessão:

~~~powershell
New-Item -ItemType File -Path './isolar-p1-n2'
Remove-Item -LiteralPath './isolar-p1-n2'
~~~

Escolhe o líder real pelos eventos LIDER mais recentes. O isolamento bloqueia DATA, eleição e TCP, mas permite IN. Para falha abrupta, anota primeiro p1;n2;<instante em ms> em falhas.txt e termina apenas o PID desse nó.

Portos: p0 7000–7002, p1 7003–7005, p2 7006–7008. Não executar sessões em paralelo: partilham portos e tópicos.

## Verificação

Executa sequencialmente, com a demonstração terminada:

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "$sdSprint/ensaiar.ps1" -Ensaio unit
powershell -NoProfile -ExecutionPolicy Bypass -File "$sdSprint/ensaiar.ps1" -Ensaio smoke
powershell -NoProfile -ExecutionPolicy Bypass -File "$sdSprint/ensaiar.ps1" -Ensaio all
powershell -NoProfile -ExecutionPolicy Bypass -File "$sdSprint/ensaiar.ps1" -Ensaio falhas
~~~

Unit dispensa IPFS. Smoke usa prazos curtos; all usa os prazos da ficha e executa os 21 ensaios normais. Também se pode selecionar 9a, 9b, 9c, 9d, 9e ou uma falha pelo nome. Cada execução usa uma pasta nova.

As cinco falhas compilam variantes temporárias, preservando as fontes corretas. Os ensaios têm exclusão mútua e verificam os portos antes de arrancar. Os arranques fixos usam uma barreira; o escalonamento continua real. O limite de observação é 60 s, sendo a ausência de líder explicitamente registada.

~~~powershell
java -cp "$sdSprint/raft04-no/out" raft04.RaftCheck 3 <pasta>
java -cp "$sdSprint/raft04-no/out" raft04.CausalCheck 3 <pasta>
python "$sdSprint/testes/resumir.py" <pasta-da-campanha>
powershell -NoProfile -ExecutionPolicy Bypass -File "$sdSprint/parar-ipfs.ps1"
~~~

Os verificadores devolvem 0 para propriedades auditadas válidas, 2 para violações e 1 para entrada inválida. Perdas esperadas são medidas, mas não são por si só violações da eleição. A análise só comprova os dados observados.

## Projetos e entrega

| Projeto | Entrada | Responsabilidade |
|---|---|---|
| raft04-produtor | Producer N [api] | IN, monitorização, registo dos envios |
| raft04-no | PartitionNode k i [N] | Eleição, escrita, lotes, recuperação TCP |
| raft04-processador | AlarmProcessor N [api] | Observar, deduplicar, emitir alarmes |
| raft04-no | RaftCheck / CausalCheck | Auditar ficheiros |

Batch, SensorRecord, Wire, VectorClock, Partitioner e IpfsPubSub mantêm formatos compatíveis entre projetos. Os auxiliares obsoletos do nó foram removidos desta cópia; a COMINDIR04 original foi conservada.

Branch: e1-comindir05. O material é partilhado num PR interno ao fork para estudo, com base em e1-comindir04. A submissão para avaliação ao docente é um passo separado: revê o relatório, prepara CA1–CA4 e segue o [guia](../../Readme.md).
