# E1-COMINDIR02 — resultados e reflexão

[Enunciado: Issue #15](https://github.com/cunhaestgv/SD-2026-2027/issues/15).

## Condições dos ensaios

Medições de 19–20/10/2026, em Windows, JDK 25.0.2 e Kubo 0.39.0. Os três projetos foram compilados com `--release 11`. Todos os processos correram na mesma máquina, partilhando CPU e disco.

No cenário de um nó, os programas usaram a API 5001; o segundo daemon permaneceu disponível. No cenário de dois nós locais, R2 e R3 usaram a API 5002, ligada ao primeiro nó. Estes resultados não representam ensaios entre máquinas nem através de NAT ou firewall. Os logs brutos não integram esta entrega; apresentam-se os resultados registados.

## Pontos 6, 9 e 13 — recuperação e execução

| Ponto | Execução observada | Resultado |
| --- | --- | --- |
| 6(a) | Cinco leituras, N=1 e três réplicas | Líder e três réplicas com cinco registos iguais |
| 6(b) | R2 parada durante seq 6–8; reiniciada; publicadas seq 9–10 | R2 permaneceu com cinco escritos e dois retidos; não convergiu imediatamente |
| 6(b), continuação | Mais nove leituras, uma de cada vez | Seq 19 desencadeou a recuperação: décimo primeiro retido; uma ligação TCP transferiu seq 6–8; os quatro ficheiros ficaram iguais |
| 6(c) | R3 com `-Ddescartar=20`; publicada seq 20 e cessado o tráfego | R3 permaneceu em seq 19, sem retidos e sem recuperar automaticamente a cauda |
| 6(c), continuação | Publicadas onze leituras adicionais | Seq 31 desencadeou uma ligação para recuperar seq 20; convergência sem duplicados |
| 6(e) | R3 parada, ficheiro removido e reinício vazio; `rajada 20 10` | Uma ligação de R3 transferiu todo o histórico anterior, seq 1–31; terminou com 51 registos iguais ao líder, zero retidos e convergência em 392 ms |
| 9 e 13 | Pedidos TCP dirigidos aos três líderes | Os portos 7000/7001/7002 aceitaram ligações; pedidos válidos terminaram em `FIM`; pedidos mal formados ou invertidos receberam `ERRO`; intervalo além do histórico devolveu apenas `FIM` |

Em 6(e), a primeira publicação foi `1792495982071` e a última escrita necessária das réplicas foi `1792495982463`: diferença **392 ms**. Este valor inclui a rajada e o tempo até existir tráfego suficiente para desencadear a recuperação. Não é uma medição isolada da duração da transferência TCP de 31 registos. A instância nova de R3 abriu apenas **uma** ligação; a ligação anterior de R2 não integra este contador.

Nos cenários N=1, a ficha exige **três réplicas**, mas `PartitionCheck` deve verificar **R=2**. O diagnóstico de R incorreto e código 1 foram preservados; a igualdade dos quatro ficheiros foi verificada separadamente. O verificador não foi alterado para esconder esta diferença entre as duas configurações pedidas.

Os resultados individuais de 6(d) e 6(f) constam da secção de medições.

## Pontos 7 e 12 — particionamento

Para S01–S20, N=3 distribuiu os sensores em **7/7/6**. Ao passar para N=4, **13/20 (65%)** mudaram de partição.

Em **12(a)**, as leituras S01–S06 produziram dois registos por partição e cada registo ficou nas duas réplicas atribuídas. O total foi seis e `PartitionCheck` devolveu 0.

As três execuções de **12(b)**, **12(c)** e **12(f)** estão na secção de medições, com distribuição por partição, tempos e comparação de débito.

### 12(d) e 12(e) — interrupções

Em **12(d)**, o produtor publicou **1071 / 1050 / 879** leituras para p0/p1/p2. Os líderes guardaram **1071 / 17 / 879**. Assim, **1033 leituras de p1** não foram registadas; p0/p2 continuaram e todas as réplicas ficaram iguais aos históricos existentes. Depois de confirmar o líder reiniciado, a leitura adicional `S02 25.0` foi escrita como seq 18 em p1 e replicada. Foi uma prova separada do reinício, não uma retransmissão das leituras perdidas.

Em **12(e)**, os líderes guardaram **1045 / 1059 / 896** registos. O arranque de R1 demorou o suficiente para as suas subscrições só ficarem ativas depois da rajada; ficou com o prefixo anterior e sem registos retidos que desencadeassem recuperação. Não houve publicações adicionais após a rajada:

| Réplica/partição | Escritos / líder | Em falta | Ligações TCP | Registos TCP | Retidos | Convergiu? |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| R1/p0 | 19 / 1045 | 1026 | 0 | 0 | 0 | Não |
| R1/p1 | 16 / 1059 | 1043 | 0 | 0 | 0 | Não |
| R2/p1 | 1059 / 1059 | 0 | 0 | 0 | 0 | Sim |
| R2/p2 | 896 / 896 | 0 | 0 | 0 | 0 | Sim |
| R3/p0 | 1032 / 1045 | 13 | 177 | 214 | 9 | Não |
| R3/p2 | 894 / 896 | 2 | 142 | 177 | 1 | Não |

## Ponto 14 — falhas deliberadas

| Falha introduzida | Lado onde surgiu | Exceção obtida (ou «nenhuma») | Os ficheiros divergiram? | Recuperou? De que forma? | O que permitiu concluir |
| --- | --- | --- | --- | --- | --- |
| Réplica parada durante `rajada 1000` e arrancada de novo | R2 | Nenhuma | Sim durante a paragem; não no fim | Após 11 novas leituras, uma ligação TCP recuperou os 1000 registos; quatro ficheiros com 1016 registos iguais | Reiniciar preserva o prefixo, mas só tráfego posterior suficiente desencadeia recuperação |
| Réplica com `-Ddescartar` igual ao seq da última leitura | R3 | Nenhuma | Sim: 4 de 5 registos, zero retidos | Não, sem mensagens posteriores | A cauda perdida não é recuperada automaticamente |
| Líder anuncia `10.255.255.1` | Cliente TCP de R3 | `SocketException: Permission denied: getsockopt`, duas vezes | Sim: R3 com zero escritos e 12 retidos; líder com 13 | Não; duas tentativas, zero ligações estabelecidas | O ambiente negou a conexão ao endereço anunciado. Demonstra falha/nova tentativa; o timeout de 5000 ms foi comprovado separadamente no teste de componente |
| Porto TCP ocupado no arranque do líder | Líder | `BindException: Address already in use: bind` | Sem ficheiro do líder nem progresso; comparação não aplicável | Não enquanto o porto está ocupado; processo terminou | A indisponibilidade do porto impede o arranque do líder |
| Líder fecha resposta a meio, sem `FIM` | Cliente TCP de R3 | `EOFException: Resposta interrompida antes de FIM` | Sim após resposta parcial: 15 escritos, 11 retidos, líder com 36 | Sim depois de repor o líder correto e publicar a leitura seguinte: duas ligações no total, 20 registos TCP, quatro ficheiros com 37 registos iguais | Os dez registos da primeira resposta foram conservados; a nova tentativa pediu apenas o intervalo ainda ausente |
| Histórico do líder apagado, ficheiros das réplicas intactos | Líder reiniciado e réplicas | Nenhuma; duplicados assinalados | Sim: seis linhas em cada ficheiro, mas conteúdo divergente | Não | Reutilizar seq 1–5 com outros valores não corrige o histórico das réplicas; igualdade de contagens não prova igualdade de conteúdo |
| Líder p1 parado durante rajada | Entrada da partição p1 | Nenhuma no produtor | Réplicas iguais aos líderes, mas 1033 leituras publicadas não chegaram ao histórico de p1 | O líder reiniciado aceitou uma leitura de prova como seq 18; as 1033 leituras antigas não foram recuperadas | p0/p2 continuaram; PubSub não conserva histórico para o líder desligado e `IN` não permite detetar/reparar essas perdas |
| Produtor com N=4, líderes com N=3 | Encaminhamento no produtor e validação nos líderes | Nenhuma; `PARTIÇÃO ERRADA` nos líderes | Réplicas iguais aos líderes, mas só 72 das 200 publicações foram registadas | Não | Consistência entre cópias não prova que todas as leituras do produtor entraram no sistema; a configuração N tem de coincidir |
| Duas instâncias de R2 na mesma pasta | Escrita dos dois processos e verificador | Nenhuma exceção de I/O observada; verificador acusa sequências inválidas | Sim: R2-p1 com 144 linhas para 72 do líder; R2-p2 com 108 para 54 | Não | `synchronized` protege objetos dentro de uma JVM, não ficheiros partilhados por processos diferentes |

**Nota sobre `PartitionCheck`:** no caso do endereço errado, R3 ainda não criou ficheiro; os outros dois ficheiros iguais satisfazem o R=2 fixo do verificador e este devolve 0. A Parte A exige uma terceira réplica. Os contadores observados de R3 demonstraram a falta, pelo que esse código 0 não foi tratado como convergência das três réplicas. No caso das duas instâncias, ficheiros maiores que o líder são inválidos; não representam faltas negativas.

## Ponto 15 — construções usadas

A tabela relaciona cada construção exigida com um exemplo no código. As garantias são locais e condicionadas aos pressupostos indicados; os resultados experimentais das falhas constam da tabela do ponto 14.

| Construção | Onde a usou | O que ficou a ser garantido | O que continua a não ser garantido |
| --- | --- | --- | --- |
| Endereço e porto do líder em cada `REG` | `Wire.reg`, chamado por `PartitionLeader.acceptMessage`; contacto atualizado em `PartitionState.receive` | Cada mensagem válida traz informação de contacto; a réplica usa a mais recente recebida, incluindo num duplicado ou descarte simulado | Autenticidade, alcance do endereço, disponibilidade do líder e descoberta de mudanças sem novas mensagens |
| `ServerSocket` / `accept()` no líder | `PartitionLeader.run`, com porto `7000 + k` e chamada sequencial de `handleRecovery` | O líder aceita ligações TCP no porto reservado e atende uma ligação de cada vez; uma falha de ligação tratada não encerra o ciclo de aceitação | Atendimento simultâneo, ausência de filas/timeouts, proteção contra clientes lentos ou continuidade após falha do processo |
| `LIMITE_RETIDOS` | Condição `pending.size() > Wire.LIMITE_RETIDOS` em `PartitionState.receive` | Com mais de 10 retidos e lacuna inicial, a própria thread tenta recuperar o primeiro intervalo em falta; tolera alguma desordenação antes de pedir | Prova de perda, memória limitada a 10, recuperação sem tráfego posterior ou convergência quando o líder é inacessível; várias lacunas também podem deixar mais de 10 retidos após um pedido |
| Protocolo `PEDIDO` / registos / `FIM` | `PartitionState.recover` envia o intervalo; `PartitionLeader.handleRecovery` devolve linhas e `FIM`, ou `ERRO` | Pedido delimitado por linha, resposta com termo explícito; a réplica valida sequência/intervalo e deteta EOF ou `FIM` prematuros, preservando o que já escreveu | Atomicidade da recuperação inteira, autenticidade do conteúdo ou tentativa autónoma após uma interrupção; é necessária nova receção para tentar outra vez |
| `connect` e `setSoTimeout` com `T_TCP_MS` | `PartitionState.recover`; `PartitionLeader.handleRecovery` configura também o timeout de leitura do pedido | O estabelecimento e as esperas de leitura têm timeout de 5000 ms; falhas são reportadas sem apagar o estado já obtido | Prazo total de 5000 ms para todo o histórico, limite para escritas bloqueadas ou garantia de que um pedido terminará com sucesso |
| `RecordFile.range` sobre o ficheiro do líder | `PartitionLeader.handleRecovery` chama `file.range(from, to)` sob o monitor de `file`, após limitar `to` a `last` | A resposta usa o histórico local persistido, seleciona o intervalo inclusivo na ordem do ficheiro e não lê em simultâneo com um `append` do mesmo objeto | Leitura indexada, custo independente do tamanho do histórico, reparação de ficheiro apagado/corrompido ou proteção contra alterações por outra JVM |
| Função de partição `floorMod(hashCode, N)` | `Partitioner.of`, usado em `Producer.publish`, `PartitionLeader.acceptMessage` e `PartitionCheck` | Para a mesma cadeia e N, todos os processos Java obtêm a mesma partição em `[0,N−1]`; hashes negativos não originam índices negativos | Equilíbrio da frequência das leituras, detetar toda a configuração incompatível ou preservar a colocação quando N muda |
| Sequência independente por partição | Campo `last` de cada `PartitionLeader`, inicializado com `file.lastSeq()`; `PartitionState.lastSeq` e ficheiro próprios | Com o histórico preservado e um líder por partição, a numeração prossegue após reinício; a réplica escreve o prefixo contíguo e ignora seq repetidos | Ordem entre partições, deteção de perdas anteriores à numeração, coordenação de dois líderes da mesma partição ou continuidade depois de apagar o histórico |
| Atribuição de partições às réplicas (R = 2) | Argumentos `1 0,1`, `2 1,2`, `3 2,0` em `PartitionReplica`; contagem offline em `PartitionCheck` | Com esta configuração, p0 existe em R1/R3, p1 em R1/R2 e p2 em R2/R3; o verificador denuncia uma contagem diferente | Reconfiguração automática, promoção de uma réplica, imposição de R=2 pelo processo de arranque ou isolamento de duas instâncias com o mesmo id e pasta |
| Secção sincronizada no líder (`seq` + `append`) | `synchronized(file)` em `PartitionLeader.acceptMessage`; o mesmo monitor protege a leitura de `last` e `range` | A atribuição, escrita e atualização de `last` são observadas como uma operação indivisível pela atividade de recuperação na mesma JVM; a publicação acontece depois da escrita | Transação entre disco e PubSub, resistência absoluta a falha de armazenamento, bloqueio entre processos ou sincronização entre líderes de partições distintas |

## Ponto 16 — medições e comparação

Três execuções por configuração; médias aritméticas. Convergência = última escrita efetiva da réplica mais lenta − primeira publicação, apenas com ficheiros iguais. Débito = registos escritos pelos líderes / intervalo entre primeira e última escrita. Todos os processos na mesma máquina. No cenário de um nó as aplicações usaram API 5001; o segundo daemon permaneceu disponível.

### Recuperação — 1000 registos, uma partição, um nó

A COMINDIR01 mantém a CLI original `rajada 1000`, sensor SIM e publicação pelo próprio líder; a COMINDIR02 usa `rajada 1000 10` e produtor separado. A cópia antiga tem apenas instrumentação após append e no fim da última publicação.

| Métrica | COMINDIR01 | COMINDIR02 sem perdas simuladas | COMINDIR02 perda 0,2 em R3 |
| --- | ---: | ---: | ---: |
| Envio (ms) | 6509.67 | 8088.00 | 6676.67 |
| Convergência (ms) | 6518.67 | 9512.33 | Não convergiu em 3/3; sem média de convergência |
| Débito no líder (registos/s) | 153.95 | 108.16 | 118.36 |
| Ligações TCP de recuperação | 0.00 | 0.00 | 158.00 |
| Registos obtidos por TCP | 0.00 | 0.00 | 204.33 |
| Em falta R1 / R2 / R3 | 0.00 / 0.00 / 0.00 | 0.00 / 0.00 / 0.00 | 0.00 / 0.00 / 9.33 |
| Retidos no fim (total) | 0.00 | 0.00 | 7.33 |

### Particionamento — 3000 registos, um nó

| Métrica | N=1 | N=3 uniforme | N=3 chave quente |
| --- | ---: | ---: | ---: |
| Registos por partição (p0 / p1 / p2) | 3000.00 | 1058.33 / 1023.67 / 918.00 | 2588.33 / 220.00 / 191.67 |
| Desequilíbrio máximo / média | 1.0000 | 1.0583 | 2.5883 |
| Débito agregado (registos/s) | 146.99 | 158.47 | 158.32 |
| Envio (ms) | 19194.00 | 19295.33 | 18659.00 |
| Convergência (ms) | 20952.67 | 19319.33 | 19383.67 |
| Registos guardados R1 / R2 / R3 | 3000.00 / 3000.00 / 3000.00 | 2082.00 / 1941.67 / 1976.33 | 2808.33 / 411.67 / 2780.00 |
| Partições indisponíveis com líder p1 parado | Não existe p1; se p0 parar, toda a escrita fica indisponível | p1 | p1 (mesma atribuição estática) |

A falha do líder p1 foi ensaiada com carga uniforme no ponto 12(d); a coluna quente indica a consequência da mesma atribuição, não um segundo ensaio de falha com chave quente.

### Perda 0,2 em R3 — três execuções em um e dois nós

Instantes em milissegundos de época, registados depois de append. O «último escrito» pode ser anterior ao seq=1000. Faltas são registos do líder ainda ausentes no ficheiro; retidos já chegaram mas ainda não puderam ser escritos.

| Nós | Execução | R3 último seq escrito | R3 instante última escrita | Ligações TCP R3 | Registos TCP R3 | R3 retidos | R3 em falta | Convergência |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | 6d-1 | 993 | 1792520224648 | 152 | 192 | 5 | 7 | NA |
| 1 | 6d-2 | 992 | 1792520458259 | 166 | 230 | 7 | 8 | NA |
| 1 | 6d-3 | 987 | 1792520689141 | 156 | 191 | 10 | 13 | NA |
| 2 | 6f-1 | 992 | 1792520258462 | 151 | 183 | 5 | 8 | NA |
| 2 | 6f-2 | 992 | 1792520489947 | 157 | 208 | 7 | 8 | NA |
| 2 | 6f-3 | 986 | 1792520722129 | 162 | 204 | 10 | 14 | NA |

Média em dois nós: envio 7956.33 ms; débito 119.23 registos/s; ligações TCP 156.67; registos TCP 198.33; retidos 7.33; faltas R1/R2/R3 0.00 / 0.00 / 10.00.

### Amostras individuais

| Caso | Publicadas | Nos líderes | p0/p1/p2 | Envio ms | Débito reg/s | Convergência ms | Retidos | PartitionCheck |
| --- | ---: | ---: | --- | ---: | ---: | --- | ---: | ---: |
| 6-baseline-1 | 1000 | 1000 | [1000] | 6726 | 108.342 | 9555 | 0 | 1 |
| 6d-loss-1 | 1000 | 1000 | [1000] | 5964 | 127.959 | NA | 5 | 1 |
| 6f-two-nodes-1 | 1000 | 1000 | [1000] | 8712 | 109.016 | NA | 5 | 1 |
| 12b-uniform-1 | 3000 | 3000 | [1073, 1012, 915] | 21228 | 142.796 | 21239 | 0 | 0 |
| 12c-hot-1 | 3000 | 3000 | [2601, 226, 173] | 17721 | 161.178 | 18843 | 0 | 0 |
| 12f-single-1 | 3000 | 3000 | [3000] | 21729 | 129.439 | 23333 | 0 | 1 |
| 6-baseline-2 | 1000 | 1000 | [1000] | 8968 | 103.552 | 9908 | 0 | 1 |
| 6d-loss-2 | 1000 | 1000 | [1000] | 8131 | 117.481 | NA | 7 | 1 |
| 6f-two-nodes-2 | 1000 | 1000 | [1000] | 7520 | 124.953 | NA | 7 | 1 |
| 12b-uniform-2 | 3000 | 3000 | [1050, 1017, 933] | 19764 | 152.687 | 19807 | 0 | 0 |
| 12c-hot-2 | 3000 | 3000 | [2582, 201, 217] | 21890 | 135.759 | 22276 | 0 | 0 |
| 12f-single-2 | 3000 | 3000 | [3000] | 20663 | 135.404 | 22312 | 0 | 1 |
| 6-baseline-3 | 1000 | 1000 | [1000] | 8570 | 112.600 | 9074 | 0 | 1 |
| 6d-loss-3 | 1000 | 1000 | [1000] | 5935 | 109.625 | NA | 10 | 1 |
| 6f-two-nodes-3 | 1000 | 1000 | [1000] | 7637 | 123.716 | NA | 10 | 1 |
| 12b-uniform-3 | 3000 | 3000 | [1052, 1042, 906] | 16894 | 179.921 | 16912 | 0 | 0 |
| 12c-hot-3 | 3000 | 3000 | [2582, 233, 185] | 16366 | 178.031 | 17032 | 0 | 0 |
| 12f-single-3 | 3000 | 3000 | [3000] | 15190 | 176.129 | 17213 | 0 | 1 |

Para N=1, a ficha exige três réplicas, mas `PartitionCheck` mantém R=2 conforme o ponto 11: o código 1 nesses ensaios inclui esse diagnóstico. A igualdade dos ficheiros foi verificada separadamente nos ensaios. Não se alterou o verificador para ocultar a diferença.

## Ponto 17 — reflexão crítica

### 17.1 — custo da recuperação

Com um nó e perda 0,2 em R3, cada rajada de 1000 exigiu, em média, **158 ligações TCP e 204,33 registos recuperados**. Isso representa cerca de **20,43% de registos adicionais** face aos 1000 REG publicados pelo líder; não é uma comparação de bytes. R3 terminou com médias de 9,33 registos em falta e 7,33 retidos, sem convergir nas três execuções.

O tráfego normal publica uma vez por leitura em cada um dos troços PubSub. Uma recuperação acrescenta pedidos e respostas unicast por réplica; a comparação de **bytes** na rede exigiria medição própria e não se deduz apenas da contagem de registos. O líder paga leitura de histórico/CPU/envio, a réplica paga conexão/receção/aplicação. As outras réplicas não recebem esse fluxo TCP, mas podem ser afetadas pela competição pelos recursos do líder e pela fila serial de pedidos.

### 17.2 — critério de decisão

Reduzir o limiar de 10 para 1 dispara com dois retidos, antecipando recuperação e aumentando a sensibilidade a simples desordenação. Aumentar para 1000 exige 1001 retidos, elevando atraso/memória e a probabilidade de uma série terminar sem disparar. Em 6(c), a perda da última leitura deixou zero retidos e não desencadeou recuperação. Foram necessárias **11 leituras posteriores** para a recuperar.

Para reconhecer uma cauda perdida seria necessária informação adicional sobre a sequência final/mais recente do líder mesmo sem novos dados. Anúncios periódicos/heartbeats seriam uma família de mecanismos para tal, mas são expressamente excluídos. A conclusão para esta implementação é que **não resolve a recuperação da cauda sem tráfego posterior suficiente**.

### 17.3 — concentração de pedidos

Se 300 réplicas diferentes detetarem a mesma lacuna e cada uma fizer uma tentativa bem-sucedida, chegam 300 conexões/pedidos; falhas e repetições podem aumentar tentativas. Com serviço serial e duração média t por pedido, sem sobreposições no serviço, a última esperaria aproximadamente 299t e terminaria cerca de 300t após o início do primeiro. Isso é um modelo simplificado; filas limitadas, recusas e timeouts alteram o resultado.

Threads por conexão permitiriam atender pedidos simultâneos usando mecanismos Java abrangidos pelo tema da concorrência. Contudo, o ponto 4 exige que **esta implementação atenda um de cada vez**; não foi substituída por um servidor concorrente. Mesmo com várias threads, o trabalho e o tráfego de responder às réplicas continuam a crescer com o seu número, e o acesso ao histórico exige proteção.

### 17.4 — acoplamento

O endereço anunciado tem de ser alcançável pelas réplicas para o porto de recuperação. Numa rede privada pode ser necessário usar um endereço/interface acessível e uma configuração de encaminhamento/firewall que permita a conexão. Um IP privado ou `127.0.0.1` só serve quando é alcançável no contexto concreto; `127.0.0.1` numa máquina remota aponta para essa própria máquina.

O sucesso do PubSub não prova o alcance direto do servidor TCP. Sem endereço alcançável, a recuperação falha e os retidos podem crescer. Este desenho não inclui descoberta de rotas, travessia automática de NAT ou mudança de líder.

### 17.5 — histórico

Uma réplica vazia que começa a receber depois de existir histórico pode pedir todo o prefixo anterior ao primeiro registo retido numa conexão. Uma ligação não significa custo constante: `range` lê o histórico inteiro e a resposta transfere todos os registos pedidos. Durante esse serviço, as restantes recuperações esperam.

No teste da réplica parada, uma ligação recuperou 1000 registos. Entre a escrita do primeiro e do último registo recuperado decorreram **787 ms**. A extrapolação `787 × 1 000 000 / 1000 = 787 000 ms` dá cerca de **13,1 minutos**. É uma estimativa limitada: exclui o tempo anterior à primeira escrita e não prevê a leitura de um histórico maior, memória, rede remota ou concorrência. Não foi executado um ensaio com um milhão de registos. Um atendimento dessa duração atrasaria os pedidos seguintes e poderia causar timeouts. Um snapshot/compactação poderia evitar reaplicar todo o histórico antigo, mas está fora do âmbito e não foi implementado.

### 17.6 — distribuição de carga

A distribuição média uniforme foi **1058,33 / 1023,67 / 918,00**. Com chave quente foi **2588,33 / 220,00 / 191,67**: p0 recebeu cerca de **86,28%** da carga e o desequilíbrio foi **2,5883**. Cada rajada quente teve 2400 leituras de S01; outros sensores também pertencem a p0. O particionamento por sensor não resolve esta concentração. O débito uniforme N=3 foi 158,47 registos/s contra 146,99 em N=1, ganho de cerca de 7,8% neste ambiente partilhado; com chave quente foi 158,32 registos/s. Estes ensaios não mostraram uma degradação relevante de débito com a chave quente, apesar da concentração observada.

Uma chave composta por sensor e um elemento variável por leitura poderia espalhar leituras de S01 por várias partições. Perder-se-ia a propriedade de todas as leituras desse sensor serem ordenadas pelo mesmo líder. Esta é a discussão da alternativa pedida na reflexão; **a implementação continua a usar o identificador do sensor**, conforme a especificação, e não acrescenta ordem global.

### 17.7 — alteração do número de partições

Para S01–S20, **13 dos 20 sensores (65%)** mudam ao passar de N=3 para N=4. Alterar a função de encaminhamento sem migrar o passado deixa dados de sensores nas partições antigas e novos registos nas novas. Seria necessário redistribuir ficheiros e tratar a continuidade/configuração das escritas. Essa operação não existe na ficha.

Hashing consistente é uma família de funções concebida para reduzir deslocações quando o conjunto de destinos muda; é explicitamente excluída, tal como reparticionamento dinâmico. Mencioná-la identifica o limite, não autoriza trocar `floorMod(hashCode,N)`.

### 17.8 — disponibilidade

Na COMINDIR01, parar o único líder afeta a escrita de todo o conjunto. Com três líderes, parar p1 afeta a aceitação e a recuperação de p1; p0/p2 continuam. Cópias existentes preservam o que receberam, mas não se promovem automaticamente a líder. O particionamento limita o alcance da falha e não a resolve.

Outra máquina assumir a partição exigiria um mecanismo para escolher e coordenar quem lidera, manter histórico/seq e atualizar o contacto sem líderes concorrentes. Eleição/consenso estão fora do âmbito; a solução implementada não fornece failover.

### 17.9 — troço produtor → líder

No teste 12(d), das **1050 leituras destinadas a p1**, apenas 17 foram guardadas: **1033 perderam-se**. A diferença foi identificada na comparação das contagens publicadas com os ficheiros, não pelo protocolo. As partições p0 e p2 guardaram as suas 1071 e 879 leituras. Como `IN` não tem seq do produtor, os ficheiros dos líderes podem continuar contíguos apesar de leituras terem desaparecido antes de receber número. O produtor e as réplicas não detetam automaticamente essa falta.

Uma recuperação semelhante precisaria de identificar produtores e leituras, conservar as leituras na origem, anunciar como as obter e permitir ao líder pedir as ausentes; também teria de definir quando pode descartar esse histórico. Não se acrescentam esses mecanismos nem confirmações ao protocolo pedido.

### 17.10 — segurança

O código atual aceita o contacto de cada `REG` válido. Uma mensagem forjada pode induzir uma réplica a ligar a outro endereço, obter respostas falsas formalmente válidas ou falhar repetidamente; a validação sintática/seq não comprova a autenticidade dos dados. Muitas conexões, em particular clientes que não enviam logo um pedido, podem ocupar o servidor serial e atrasar recuperações legítimas. O timeout de leitura limita uma espera, mas não impede sobrecarga por pedidos repetidos.

A verificação da origem teria de ocorrer antes de confiar nas mensagens de entrada/dados e no contacto anunciado; o serviço de recuperação também necessitaria de controlar os seus clientes. O campo `from` identifica uma origem no protocolo PubSub, mas, por si só, não define que identidade está autorizada a liderar uma partição. A classe atual só extrai `data` e não faz essa associação. A ficha não fornece um protocolo de autenticação/autorização; o sistema não resolve essa ameaça e a implementação não lhe acrescenta mecanismos de segurança fora da especificação.
