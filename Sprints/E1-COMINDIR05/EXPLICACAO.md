# Explicação da COMINDIR05

## O problema

Há sensores a enviar temperaturas. Guardamos cópias em vários processos para sobreviver a uma falha. Alguém precisa de decidir a ordem: leitura 1, leitura 2, leitura 3. Esse alguém é o **líder**.

Se dois processos atribuíssem números à vontade, poderiam chamar “leitura 3” a temperaturas diferentes. Esta ficha elege o líder e permite reconhecer uma liderança mais recente. É uma parte do Raft: ainda falta confirmar cada escrita por maioria.

## p1 e n1 são coisas diferentes

Há três **partições de dados**, p0, p1 e p2. Uma função baseada no sensor escolhe a partição. Cada partição tem três processos: n1, n2 e n3. Existem nove nós, três líderes em condições normais e seis seguidores.

p1/n2 significa nó 2 do grupo de p1. O n2 de p0 é outro processo. Os termos são independentes: p0 pode estar no termo 2 e p1 no 7.

Uma **partição de rede** é uma falha de comunicação entre processos que continuam a funcionar. O ficheiro isolar-p1-n2 simula esse corte.

## Segue uma leitura

~~~mermaid
sequenceDiagram
    participant P as Produtor
    participant K as Kubo PubSub
    participant L as Líder de p1
    participant S as Seguidores de p1
    participant A as Processador
    P->>K: IN S02 20.1
    K->>L: IN
    Note over L: atribui seq, termo, L e V; grava
    Note over L: leitura pendente até ao próximo lote
    L->>K: LOTE + heartbeat
    K->>S: LOTE
    Note over S: grava a cópia ou recupera por TCP
    K->>A: LOTE
    Note over A: se necessário, publica ALARM-S02 como IN
~~~

**PubSub** é uma difusão por tópico. O produtor publica em raft04-in-p1 sem escolher um endereço de líder. Todos os nós de p1 recebem, mas apenas o líder atribui sequência; os outros assinalam IGNORADO.

O líder grava primeiro e coloca a leitura nas pendentes. A cada 2 s publica-as num LOTE. Sem leituras novas, publica um lote vazio: o **heartbeat**, que permite aos outros renovar o prazo de espera.

Se faltar uma sequência, um seguidor pede um intervalo ao líder por TCP. PubSub distribui novidades; TCP recupera dados já guardados.

## Como nasce um líder

Todos arrancam como seguidores e sorteiam um prazo de 6 a 12 s. Um LOTE válido renova esse prazo. Quando expira:

1. O nó aumenta o termo, vota em si e guarda esse estado.
2. Passa a candidato e pede votos.
3. Com dois eleitores diferentes, torna-se líder.
4. Publica imediatamente um LOTE.

O **termo** é uma época de eleição. Não é um nó nem uma leitura. Sem maioria, o candidato espera pelo seu novo prazo e tenta num termo superior.

Maioria significa **mais de metade dos membros configurados**, mesmo que estejam desligados.

| Membros | Maioria | Falhas toleradas para eleger |
|---|---:|---:|
| 2 | 2 | 0 |
| 3 | 2 | 1 |
| 4 | 3 | 1 |
| 5 | 3 | 2 |

Um nó pode votar antes de o seu prazo terminar: o prazo controla quando se candidata, não quando responde a pedidos. Os prazos aleatórios reduzem candidaturas simultâneas.

## As condições do voto

RaftState.onVoteRequest verifica: pedido no termo atual; não ter apoiado outro candidato nesse termo; candidato com histórico pelo menos tão atualizado.

Compara-se o **termo do último registo primeiro**, depois a sequência se os termos forem iguais. Um último registo (seq=3, termo=4) é considerado mais atualizado do que (seq=8, termo=3).

VotedFor guarda a decisão. Se contém n2, pode responder outra vez ao mesmo candidato, mas não apoiar outro. O candidato usa um conjunto de eleitores: mensagens repetidas não contam como votos extra.

Ao observar um termo superior, o nó adota-o, passa a seguidor e repõe votedFor=0. Pode votar na eleição nova.

## O que tem de sobreviver a uma falha

Dois ficheiros têm funções diferentes:

- estado-p1-n3.txt, por exemplo 4;2: “estou no termo 4 e votei em n2”.
- no-p1-n3.txt: histórico com sequência, termo de criação, sensor, temperatura, instante, L e V.

Guarda-se o voto **antes de enviar a resposta**. Caso contrário, o candidato pode contá-lo, o eleitor falhar e, ao voltar sem memória, apoiar outro candidato no mesmo termo.

A ordem é decidir → gravar → enviar. Falhar entre gravar e enviar permite responder novamente ao mesmo candidato, sem criar outro eleitor.

A gravação escreve um .tmp e muda-o atomicamente para o nome definitivo. Vê-se a versão antiga ou a nova, em vez de meia linha. Um .tmp abandonado não é a versão oficial. A classe fornecida não força fsync, pelo que não oferece durabilidade completa perante falha de energia.

## Um líder por termo, mas dois podem julgar-se líderes

Duas maiorias de três têm um eleitor em comum. Dois candidatos eleitos no mesmo termo exigiriam que esse eleitor votasse nos dois. A regra de um voto por termo, preservada após reinícios, impede-o.

Mas um líder pode ficar isolado no termo 1 enquanto os outros elegem outro no termo 2. O antigo ainda não sabe. Quando recebe uma mensagem do termo 2, demite-se. Um nó no termo 4 ignora mensagens de termo 2.

O termo do LOTE identifica a liderança atual. Uma leitura criada no termo 1 conserva esse termo mesmo que depois viaje num LOTE do termo 2.

## Onde está isto no código

Lê por esta ordem:

1. [RaftState](raft04-no/src/raft04/RaftState.java): tick, onVoteRequest, onVote, onBatch, persist e adoptTerm.
2. [PartitionNode](raft04-no/src/raft04/PartitionNode.java): run liga PubSub, TCP e verificação; receiveElection liga mensagens a RaftState.
3. becomeLeader, receiveInput e sendBatch: relógios, sequência e publicação.
4. receiveBatch, recoverMissing, reconcile e replaceHistory: lacunas e divergências.
5. [HeartbeatMonitor](raft04-produtor/src/raft04/HeartbeatMonitor.java) e [AlarmProcessor](raft04-processador/src/raft04/AlarmProcessor.java): observadores do grupo.
6. [RaftCheck](raft04-no/src/raft04/RaftCheck.java) e [CausalCheck](raft04-no/src/raft04/CausalCheck.java): auditoria.

RaftState decide papéis e votos; não conhece temperaturas, PubSub ou TCP. PartitionNode coordena esses meios.

Há threads para subscrições, verificação, publicação e TCP. O monitor do nó protege a sequência e todas as transições de termo. Dentro dele pode adquirir-se o monitor de RaftState; não há caminho inverso. Evita-se um ciclo de espera entre locks.

Se um IN e uma demissão chegarem juntos, um ganha o monitor primeiro. Ou a leitura é atribuída antes da demissão, ou é ignorada depois. Não recebe sequência com termo antigo depois de o nó adotar o novo.

A publicação HTTP fica fora do monitor. Uma mensagem preparada pode chegar atrasada; os destinatários verificam o termo. Cada agendamento guarda também o termo da liderança que o criou.

## Como se desfaz uma divergência

No exemplo:

- O antigo líder guarda S02 20.4 como sequência 3, termo 1, mas falha antes de publicar.
- Os seguidores só têm 1 e 2 e elegem outro líder no termo 2.
- S02 20.6 recebe sequência 3, termo 2.
- O antigo regressa e encontra uma sequência 3 diferente.

O seguidor usa o histórico do líder atual como referência. Pede por TCP desde o início do seu último bloco de registos do mesmo termo e recua blocos até encontrar uma fronteira comum. Remove a cauda a partir da primeira diferença e aplica a do líder.

TRUNCADO de=3 descartados=1 significa que um registo local foi descartado a partir da sequência 3. A substituição do ficheiro é atómica. Uma comparação sobreposta no início de uma liderança evita esconder um prefixo divergente ao acrescentar uma cauda.

No ensaio real 9(a), os três nós terminaram com as mesmas três leituras. Perderam-se a pendente 20.4 e a 23.9 enviada durante a eleição.

## L e V não são o termo

L é o relógio de Lamport; V é um vetor com uma componente por partição. Transportam informação de ordem e causalidade. O termo serve a eleição.

Uma leitura nova atualiza os relógios recebidos e incrementa a componente da partição. Verifica-se V[k] = seq. Heartbeats e eleições não incrementam estes relógios.

O novo líder recupera sequência e relógios do último registo. Se começa com seq=2 e V[1]=0, a próxima leitura tentaria seq=3 e V[1]=1. Uma guarda recusa a escrita; sem ela, CausalCheck detetaria o ficheiro inválido.

O processador deduplica por partição e pelo par (seq, termo do registo). Os pares (7,2) e (7,3) são diferentes e podem gerar dois alarmes. Transportar novamente (7,2) depois de mudar de líder não cria outro registo.

## Os limites que tens de explicar

Falta de heartbeats é uma **suspeita**, não prova de falha: pode ser corte de rede ou uma pausa longa.

O líder isolado ainda aceita IN, porque o produtor alcança ambos os lados neste ensaio. Essas escritas podem desaparecer na reparação. Sem confirmação por maioria, não há uma garantia CP completa.

Sem líder, ninguém aceita as leituras. O produtor publica uma vez e não as retém. Para impedir perda de escritas confirmadas seria preciso commit por maioria. Para preservar leituras ainda sem confirmação, seria preciso retenção e reenvio duráveis, com identificadores estáveis e deduplicação. Esses mecanismos estão excluídos da ficha.

Um seguidor isolado aumenta o termo nas suas tentativas. Ao regressar pode derrubar um líder saudável. Pre-Vote consultaria se há apoio antes de aumentar o termo; também está excluído.

## Preparação para a apresentação

Começa pelos ficheiros de 9(a), só em p1. Segue CANDIDATO → VOTO → LIDER, depois TERMO e TRUNCADO.

As ideias essenciais são: maioria sobre três membros; voto em disco antes da resposta; um voto duplo seria necessário para dois líderes no mesmo termo; líderes de termos diferentes podem coexistir sem se verem; a recuperação impõe o histórico do líder atual; ainda há perdas por faltar commit.

Se n1 também falhar enquanto n2 está parado, n3 tenta candidaturas mas não consegue dois votos. Se n2 regressar antes da eleição com o histórico mais atualizado, pode candidatar-se e vencer. Regressar depois de outro líder estar eleito não lhe devolve a liderança.

O código e o relatório ajudam a estudar, mas a avaliação oral exige explicar estas decisões com as tuas palavras.
