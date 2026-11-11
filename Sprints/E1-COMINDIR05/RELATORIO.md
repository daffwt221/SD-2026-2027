# Relatório COMINDIR05

## Condições e método

Referência: [ficha E1-COMINDIR05](https://github.com/cunhaestgv/SD-2026-2027/issues/18), atualizada em 02/11/2026. Campanha local em Windows, 11/11/2026, JDK 25.0.2, compilação Java 11 e Kubo 0.39.0. Três partições, três nós por partição, nove processos Java; processador e produtor separados. Todos os processos usam a mesma máquina e o mesmo relógio físico.

Nos ensaios normais: heartbeat 2000 ms, eleição aleatória 6000–12000 ms, verificação 200 ms, fator do detetor 3. Só 9(b) fixo altera os prazos para 6000/6000. Usa-se PubSub Kubo real através de um daemon local, sem pares externos, e recuperação TCP em loopback. Isto testa os processos e a recuperação, mas não mede uma rede física distribuída.

Cada ensaio arranca numa pasta nova, equivalente a limpar o estado do ensaio anterior. As falhas usam término forçado e os isolamentos usam os ficheiros pedidos. Falhas, cortes, reinícios e PIDs ficam registados. RaftCheck e CausalCheck são executados sobre os ficheiros depois de os processos terminarem.

Os tempos usam os instantes observados, não os tempos pretendidos pelo script. O tempo de arranque começa na entrada do primeiro nó; inclui a inicialização Java antes da primeira eleição. O arranque fixo usa uma barreira comum depois do aquecimento das classes. O sistema operativo ainda introduz diferenças de escalonamento.

A campanha anterior com Kubo 0.43.1 foi excluída das estatísticas devido a HTTP 500 da API, conforme a [nota](evidencias/execucao-20261111/NOTA.md). Uma tentativa de 9(c)-3 sobreposta a um teste auxiliar também foi excluída e repetida isoladamente; a pasta final contém a repetição. Acrescentou-se exclusão mútua aos ensaios para prevenir essa sobreposição.

## 9(a) — figura e truncagem

O líder n1 de p1, termo 1, escreveu duas leituras que chegaram aos seguidores. S02 20.4 ficou com seq=3 no seu ficheiro, sem publicação. O líder foi terminado 42 ms após o envio dessa leitura. S05 23.9 foi enviada durante a eleição.

n3 foi eleito no termo 2. S02 20.6 recebeu seq=3 nesse termo. Quando n1 regressou, adotou o termo 2, recuperou por TCP e truncou o seu registo antigo de seq=3.

Resultado: indisponibilidade 6,134 s; duas leituras perdidas, uma por truncagem e outra durante a eleição; um registo truncado; três ficheiros iguais até seq=3. Ambos os verificadores passaram. Ver [relatório do ensaio](evidencias/execucao-kubo039/9a/RaftCheck.txt).

## 9(b) — arranque

Cada célula p0/p1/p2 apresenta as três partições. “Divididos” conta termos com pelo menos dois candidatos e sem líder; um termo sem líder por falta de quórum é contado separadamente.

| Execução | Primeiro líder p0/p1/p2 (s) | Termos até líder | Votos divididos |
|---|---|---|---|
| 9b-aleatorio-1 | 7,725 / 10,081 / 9,151 | 1 / 1 / 1 | 0 / 0 / 0 |
| 9b-aleatorio-2 | 7,211 / 9,012 / 8,050 | 1 / 1 / 1 | 0 / 0 / 0 |
| 9b-aleatorio-3 | 7,453 / 7,276 / 9,591 | 1 / 1 / 1 | 0 / 0 / 0 |
| 9b-aleatorio-4 | 9,317 / 10,568 / 7,394 | 1 / 1 / 1 | 0 / 0 / 0 |
| 9b-aleatorio-5 | 9,733 / 8,537 / 8,193 | 1 / 1 / 1 | 0 / 0 / 0 |
| 9b-aleatorio-6 | 7,923 / 7,211 / 7,547 | 1 / 1 / 1 | 0 / 0 / 0 |
| 9b-aleatorio-7 | 8,594 / 8,018 / 9,822 | 1 / 1 / 1 | 0 / 0 / 0 |
| 9b-aleatorio-8 | 8,832 / 8,898 / 7,507 | 1 / 1 / 1 | 0 / 0 / 0 |
| 9b-aleatorio-9 | 8,627 / 8,569 / 8,241 | 1 / 1 / 1 | 0 / 0 / 0 |
| 9b-aleatorio-10 | 8,269 / 8,864 / 7,359 | 1 / 1 / 1 | 0 / 0 / 0 |
| 9b-fixo-1 | 18,671 / 30,879 / 12,489 | 3 / 5 / 2 | 2 / 4 / 1 |
| 9b-fixo-2 | 12,480 / 12,480 / 12,488 | 2 / 2 / 2 | 1 / 1 / 1 |
| 9b-fixo-3 | 18,637 / 37,098 / 12,485 | 3 / 6 / 2 | 2 / 5 / 1 |
| 9b-fixo-4 | 12,431 / 18,624 / 6,597 | 2 / 3 / 1 | 1 / 2 / 0 |
| 9b-fixo-5 | 30,861 / 18,650 / 43,436 | 5 / 3 / 7 | 4 / 2 / 6 |

Nos dez arranques aleatórios: 30 eleições iniciais, todas com líder no primeiro termo; tempo mínimo/médio/máximo 7,211 / 8,452 / 10,568 s; zero votos divididos.

Nos cinco arranques fixos: 15 eleições iniciais; 1 / 3,2 / 7 termos, mínimo/médio/máximo; 33 termos de voto dividido; primeiro líder em 6,597 / 19,887 / 43,436 s. Todas as partições elegeram antes do limite de 60 s. O prazo fixo não impede necessariamente a eleição: diferenças reais de escalonamento acabam por quebrar a simetria.

## 9(c) — falha durante rajada

Cada rajada publicou as 300 leituras por 10 sensores. O líder de p1 foi terminado durante a rajada e reiniciado cerca de 30 s depois. A rajada é rápida; pode acabar antes da eleição, pelo que o número perdido depende também de quando cada mensagem foi entregue.

| Ensaio | Indisponibilidade (s) | Perdidas totais | Durante eleição | Por truncagem | Outras sem gravação observada | Registos truncados |
|---|---:|---:|---:|---:|---:|---:|
| 9a | 6,134 | 2 | 1 | 1 | 0 | 1 |
| 9c-1 | 5,947 | 86 | 80 | 6 | 0 | 6 |
| 9c-2 | 6,153 | 81 | 69 | 12 | 0 | 12 |
| 9c-3 | 6,809 | 98 | 88 | 3 | 7 | 3 |

A linha 9(a) permite comparar a carga controlada com as três rajadas. Todas as indisponibilidades cabem em ]4;12,2] s, intervalo teórico que ignora o tempo de difusão/votação. Só nas três rajadas, a indisponibilidade foi 5,947 / 6,303 / 6,809 s; perderam-se em média 88,333 leituras e truncaram-se 7 registos por falha.

Em 9(c)-3 há **sete perdas adicionais anteriores à falha que não aparecem nos registos nem nas truncagens**. Outras três leituras anteriores à falha foram efetivamente gravadas e depois truncadas. A publicação HTTP não confirma a receção pelo nó. Mensagens ainda em trânsito/no tratamento são uma explicação compatível com os ficheiros; não se atribuem essas sete perdas à eleição nem se inventa uma confirmação de entrega.

A coincidência final foi completa nas três rajadas e os verificadores passaram. Os ficheiros hb/eventos e [RaftCheck](evidencias/execucao-kubo039/9c-3/RaftCheck.txt) permitem reproduzir a classificação.

## 9(d) — isolamento e regresso

Durante o corte de 30 s, n2 continuou a julgar-se líder no termo 1; n1 foi eleito pelos dois nós comunicantes no termo 2. Foram dois líderes simultâneos de **termos diferentes**, sem violar a unicidade por termo.

O antigo líder aceitou durante o isolamento **81 IN: 60 leituras e 21 alarmes**. No total, o seu histórico divergente tinha 93 registos, incluindo 12 leituras anteriores ao corte. Ao reparar, esses **93 registos foram truncados**: 72 leituras do produtor e 21 alarmes. Nenhum registo truncado apareceu no líder final com outra sequência.

O processador observou a mudança n2/1 → n1/2, mas **zero registos de p1**: o primeiro lote com dados foi suprimido pelo isolamento e o novo líder ainda tinha histórico vazio. A rajada terminou antes de o novo líder poder receber novas leituras. Não se afirma que a carga tenha produzido escritas simultâneas em ambos os lados; demonstrou aceitação pelo antigo líder e eleição no lado maioritário.

Depois isolou-se o seguidor n2 durante 60 s, sem nova rajada. Passou pelos termos 3,4,5,6,7 e terminou o corte com estado **7;2**, enquanto o líder saudável estava no termo 2. Ao regressar, perturbou a liderança e acabou eleito no termo **8**. O processador registou n2/8. Não houve novas perdas nessa fase.

Todos os nós terminaram com o mesmo histórico. RaftCheck não encontrou dois líderes no mesmo termo, termos decrescentes ou sequências divergentes; CausalCheck passou. Ver [eventos e relatório](evidencias/execucao-kubo039/9d/RaftCheck.txt).

## 9(e) — perda de maioria

Foram terminados n2, líder, e n1. n3 tentou candidaturas nos termos 2,3,4; depois do regresso de n2 conseguiu maioria no termo 5 e foi eleito.

As 30 leituras enviadas ao longo de 30 s sem maioria perderam-se. Uma leitura inicial replicada e outra posterior à eleição foram conservadas. O tempo desde o reinício de um nó até ao primeiro LOTE do novo líder foi **9,044 s**; desde a falha do líder, **39,065 s**.

O terceiro nó ficou parado, por definição do ensaio. Assim, o estado final de p1 é n1:1, n2:2, n3:2, com coincidência até ao menor último seq, 1. Isto não significa que o nó parado tenha recuperado a cauda; os verificadores assinalam os limites comparados. Ambos passaram.

## 10 — falhas deliberadas

As variantes são compiladas separadamente e usadas apenas pelo processo do respetivo ensaio; as fontes de entrega conservam a versão correta. Não se remove uma segunda proteção para fabricar uma falha.

| Falha | Lado onde se manifestou | RaftCheck / CausalCheck | Conclusão medida |
|---|---|---|---|
| Maioria com votes.size() >= groupSize/2 | A fórmula fica errada; o fluxo real com G=3 mascarou o erro | 0 / 0, sem violação observada no cenário | OnVote só recebe votos remotos; com o próprio e um remoto já há 2. Um nó sozinho não chama OnVote e não se elegeu. Um teste unitário adicional com G=5 detetou eleição com 2 em vez de 3; não se alterou o grupo do sistema da ficha. |
| Termo e voto só em memória | Eleitor n1 de p1, depois ambos os líderes | 2 / 2 | n1 falhou **29 ms após conceder o voto**, durante a eleição. Um proxy de ensaio atrasou o VOTO real até depois do reinício. Sem persistência, n1 apoiou n2 e n3 no termo 1: dois líderes, um voto duplo, um termo decrescente e seq=1 com conteúdos diferentes. |
| Sem upToDate | Réplica atualizada que concedeu o voto; depois todo o histórico de p1 | 0 / 0; RaftCheck relatou as perdas e truncagens | O antigo n2, último registo do termo 1, foi eleito no termo 4 apesar de n1 ter último registo do termo 2. S02 20.6, já replicada, desapareceu e S02 20.4 antiga sobreviveu. Houve duas cópias truncadas de seq=3. Os verificadores atuais não provam a restrição de voto; convergir não assegura escolher o histórico correto. |
| Seguidor aplica LOTE de termo inferior | Dois seguidores de p1 | 2 / 0 | Após repetir as duas fases de 9(d), uma leitura sonda foi replicada e um LOTE real antigo foi repetido como mensagem atrasada. Houve duas aplicações do lote obsoleto; as duas cópias da sonda foram truncadas e recuperadas pelo heartbeat atual. A convergência final não apaga os eventos inválidos. |
| Novo líder com L/V a zero | Tratamento de IN pelo novo líder | RaftCheck 0, mas assinala 1 ERRO e 3 perdas; CausalCheck 0 | Com seq=2 e V[p1]=0, a leitura seguinte tentaria seq=3 e V[p1]=1. A guarda existente recusou-a antes de escrever. CausalCheck não encontra um registo inválido que nunca foi gravado. Não se removeu a guarda para fabricar um resultado. |

Os códigos 0/2 são os códigos de saída dos verificadores. Os [dados dos cinco ensaios](evidencias/execucao-kubo039/medidas.csv) e os ficheiros observacao.txt de cada pasta documentam a topologia usada.

O ensaio de persistência usa prazos controlados de 6/12 s e um proxy local que apenas atrasa a resposta, sem alterar o conteúdo ou o código da eleição. Isto permite reiniciar o eleitor **antes de o candidato receber o voto**, em vez de alegar que uma pausa do script caiu por acaso nessa janela. As testemunhas de histórico do terceiro ensaio ficam nos ficheiros testemunha-atualizado.txt e testemunha-desatualizado.txt. O ensaio do lote antigo inclui uma mensagem atrasada explícita, pois uma rede local sem filas antigas pode não entregar naturalmente esse caso.

## 11 — tabelas medidas

Na primeira tabela, o arranque normal agrega 9(a), os dez arranques aleatórios de 9(b) e as três execuções de 9(c): 42 observações, três por ensaio. Os valores fixos são apresentados separadamente acima. As médias por falha agregam as quatro falhas de líder de 9(a)/9(c). Esta definição evita misturar a fase sem quórum de 9(e) com uma eleição normal após uma falha.

| Métrica | Valor medido |
|---|---|
| Primeiro líder no arranque: mínimo / médio / máximo | 6,742 / 8,305 / 10,568 s |
| Termos até primeiro líder: aleatório / fixo | 1 / 3,2 em média; máximo 1 / 7 |
| Votos divididos: aleatório / fixo | 0 / 33 no total; 0 / 2,2 por partição/execução |
| Indisponibilidade: mínimo / médio / máximo | 5,947 / 6,261 / 6,809 s |
| Leituras perdidas por falha: durante eleição / por truncagem | 59,5 / 5,5 em média; mais 1,75 sem gravação observada |
| Registos truncados no regresso do antigo líder | 5,5 em média; 22 no total |
| Termos com mais de um líder, ensaios normais | 0 |
| Seq divergentes finais, ensaios normais | 0 |

Os valores de perdas contam apenas linhas do produtor; “registos truncados” pode incluir alarmes. Em 9(a)/9(c), os registos truncados não incluíram alarmes. As temperaturas distintas das rajadas permitem associar linhas do produtor aos registos. Sem um identificador estável, leituras com sensor e temperatura repetidos seriam ambíguas; RaftCheck assinala essa limitação.

| Cenário | Maioria aceita escritas? | Minoria aceita escritas? | Escritas minoritárias após reparação | Termos de p1 | Comportamento C/A |
|---|---|---|---|---|---|
| Falha do líder, 9(c) | Sim após eleição | Nó parado não aceita | Pendentes antigas podem ser descartadas | 1 → 2 nas três execuções | Eleição favorece C; A interrompida durante eleição; sem garantia CP de escritas |
| Líder isolado, 9(d) | Novo líder apto após eleição; rajada já tinha terminado | Sim, antigo líder aceita IN | 93 registos descartados, 72 leituras + 21 alarmes | Antigo 1 / maioria 2 | Aceitação de ambos os lados possível, reconciliação com perdas; não CP completo |
| Seguidor isolado, 9(d) | Líder saudável continua apto | Não: não é líder nem reúne votos | Nenhuma escrita nova minoritária | Maioria 2 / isolado até 7 / nova eleição 8 | Maioria favorece C; regresso causa perturbação de A |
| Dois nós parados, 9(e) | Não existe maioria até reiniciar um | Sobrevivente candidato não aceita | 30 IN não foram guardados | 2,3,4 sem líder; líder em 5 | Recusa de liderança sem quórum favorece C e sacrifica A |

“C” nesta tabela descreve a coordenação da eleição; não é uma alegação de linearizabilidade das escritas desta implementação.

## 12 — reflexão crítica

### Eleição no arranque

Os prazos aleatórios espalham as candidaturas: o primeiro pedido costuma chegar antes de os outros expirarem. As 30 eleições aleatórias iniciais terminaram no termo 1, enquanto as 15 fixas precisaram em média de 3,2 termos e produziram 33 votos divididos. A ordem de arranque deixou de determinar quem é líder: todos executam o mesmo papel inicial e qualquer um pode obter maioria. A ordem ainda pode influenciar o vencedor e o tempo observado.

É necessário o tempo de transmissão/processamento ser muito inferior ao prazo de eleição e o heartbeat caber folgadamente antes do prazo mínimo. Aqui Δ=2 s e eleição em [3Δ,6Δ]. Se o mínimo fosse inferior a Δ, poderiam iniciar eleições entre heartbeats normais, causando demissões e termos desnecessários. A aleatoriedade ajuda a terminar eleições; não substitui as regras de segurança.

### Termo e unicidade

Duas maiorias de três têm um eleitor comum. Para eleger dois líderes no mesmo termo, esse eleitor teria de apoiar ambos. Um voto por termo, persistido antes de responder, impede isso. Os conjuntos de eleitores evitam contar duplicados como maioria.

A prova não exige que todos saibam simultaneamente quem é líder. Em 9(d), n2/1 ficou isolado enquanto n1/2 era eleito. Ao reparar, o termo superior obrigou n2 a demitir-se. Não se deve confundir dois líderes de termos diferentes com dois líderes eleitos no mesmo termo.

Termo e Lamport são números lógicos monotónicos que podem ser atualizados ao observar informação remota. O termo identifica uma eleição/liderança por grupo e não aumenta por cada leitura. Lamport transporta ordem de eventos e não dá, por si, exclusividade de líder. Registos antigos conservam o seu termo de criação.

### CAP e os dois particionamentos

Partição de dados distribui sensores entre grupos independentes. Partição de rede impede comunicação entre membros de um grupo.

C significa comportar-se como uma única cópia que respeita as operações concluídas; A exige resposta sem erro a pedidos recebidos por nós operacionais; P admite perda arbitrária de comunicação entre lados. Durante uma partição de rede não se podem garantir C e A em simultâneo.

A minoria não elege porque não reúne dois dos três votos. Contudo, um líder antigo já eleito não sabe imediatamente que perdeu a maioria e esta ficha permite-lhe aceitar IN. As 72 leituras que desapareceram em 9(d) mostram por que não se pode chamar a este serviço um CP completo.

Raft completo só confirma uma escrita quando a sua replicação satisfaz o quórum e a regra de commit do protocolo; leituras consistentes também têm de verificar a liderança adequada. Isto impede a minoria de confirmar escritas durante o corte, sacrificando disponibilidade e acrescentando o tempo de comunicação com a maioria e da persistência. Não se implementou esse mecanismo porque a ficha o exclui.

### Restrição de voto e truncagem

Comparar o último termo e depois a sequência impede que um eleitor apoie um candidato com histórico menos atualizado. Protege contra impor deliberadamente uma cauda antiga, como no terceiro ensaio de falha.

Não garante que toda a leitura presente num disco sobreviva. Em 9(a), a leitura 20.4 só estava no nó parado. Os outros dois, com históricos iguais entre si, formaram uma maioria válida e elegeram outro líder. Sem commit, aquela leitura nunca teve garantia de ser conservada. O seq=3 do termo 2 passou a ser a referência e o antigo foi truncado.

### Persistência

O segundo ensaio de falha mostra que um reinício sem termo/voto pode permitir dois votos no mesmo termo, anulando a prova da interseção de maiorias. Guardar só o termo também não basta: o eleitor pode esquecer o candidato que já apoiou.

Os próprios registos persistem para recuperar dados, comparar a atualidade dos candidatos e reconstruir sequência e relógios após reinício. O ficheiro de estado não substitui o histórico.

Na ficha anterior, a incarnação persistente distinguia execuções de um líder fixo. O termo distingue épocas de eleição de um grupo, podendo a liderança mudar de processo. A escrita atómica evita ficheiros parcialmente substituídos, mas sem forçar o armazenamento não se deve prometer a mesma garantia perante corte de energia.

### Detetar, suspeitar e eleger

Um heartbeat ausente é uma suspeita. Agora uma falsa suspeita pode iniciar eleições, aumentar o termo, demitir um líder e interromper temporariamente o serviço, além do custo das mensagens. Uma pausa longa pode produzir esse efeito mesmo sem avaria permanente.

O seguidor isolado não recebe renovações do prazo e continua a aumentar o termo. Em 9(d), atingiu 7 enquanto os restantes trabalhavam no termo 2; ao regressar desencadeou a mudança para 8. Pre-Vote sondaria se o candidato conseguiria apoio antes de avançar o termo real, reduzindo a perturbação provocada por um nó isolado. Está excluído do código desta ficha.

### Limites e reenvio

Sem líder, nenhum nó atribui sequência. Em 9(e), os 30 envios durante a perda de maioria não foram guardados. Em 9(c), a rapidez da rajada deixou muitas leituras no intervalo da eleição; outras pendentes ou ainda sem entrega observada também desapareceram.

Para não perder leituras ainda não confirmadas, o produtor precisaria de retenção durável e reenvio até obter uma confirmação válida. Um identificador estável por leitura e deduplicação persistente seriam necessários: a confirmação pode perder-se e provocar repetição de uma escrita que já ocorreu. Para garantir as escritas confirmadas, seria ainda necessário commit por maioria. Estes mecanismos estão fora do âmbito e não foram acrescentados.

## Evidências e alcance

Os [ficheiros CSV](evidencias/execucao-kubo039/medidas.csv) e [JSON](evidencias/execucao-kubo039/medidas.json) são derivados dos dados pela ferramenta [resumir.py](testes/resumir.py). Os relatórios individuais conservam os candidatos, eleitores, termos, instantes e leituras perdidas.

Uma execução que passa não constitui prova de todas as interleavings possíveis. CausalCheck analisa os registos finais, não reconstitui dados que já desapareceram; RaftCheck deteta propriedades observáveis nos eventos e compara o prefixo comum. Um erro deliberado pode ser bloqueado por outra proteção ou não ser exercitado pelo cenário; esses casos são indicados, sem transformar ausência de deteção numa garantia.
