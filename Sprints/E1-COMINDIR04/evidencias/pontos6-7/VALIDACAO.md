# Validação dos pontos 6–7

Execução de 6 de novembro de 2026, com JDK 26.0.1 e compilação `--release 11`.
Passaram 70 verificações de integração e 23 verificações com métricas controladas.
`PartitionCheck`, `CausalCheck` e `HeartbeatCheck` terminaram com código 0.

O teste inicia três líderes, três réplicas, um processador e um produtor em processos Java
separados. O produtor recebe comandos pela entrada-padrão, acompanha as três partições,
regista suspeitas, avisa e mantém a publicação de uma leitura durante a falha do destino.
O fecho da entrada-padrão termina o produtor sem erro.

A API HTTP Pub/Sub é simulada. As ligações TCP de recuperação e os programas Java são reais.
Foi usado período de 1000 ms, fator 2 e verificação de 1000 ms para reduzir a duração dos testes;
os valores por omissão da aplicação permanecem 5000/3/1000. Não são os ensaios do ponto 8.

## Ficheiros da integração

Nesta pasta estão os ficheiros dos líderes, réplicas, monitores, heartbeats e `falhas.txt`
produzidos pela execução. Os três relatórios estão em `PartitionCheck.log`, `CausalCheck.log`
e `HeartbeatCheck.log`.

| Verificação | Resultado desta execução |
|---|---|
| Registos finais p0/p1/p2 | 4 / 2 / 1 |
| Réplicas | duas cópias iguais ao líder por partição |
| Causalidade | 7 eventos e 0 erros Lamport |
| Suspeitas reais com instante de falha anotado | 8 observações de participantes |
| Tempo de deteção observado | mínimo 2071 ms, média 2381,375 ms, máximo 2888 ms |
| Lotes sem comparação de bytes | 0 |
| Avisos de dados no `HeartbeatCheck` | 0 |

As latências da integração incluem a precisão de segundos do `instante` herdado da COMINDIR03.
Não devem ser interpretadas como medições de desempenho do IPFS.

## Ficheiros sintéticos

A subpasta `metricas-controladas` contém um cenário N=1, construído para ter resultados conhecidos.
Estes dados são sintéticos e não representam falhas reais:

- quatro lotes na primeira incarnação, dois vazios; intervalo médio 8966,667 ms;
- duas suspeitas reais segundo a definição do registo, uma falsa suspeita e uma suspeita inicial
  sem incarnação conhecida, classificada como indeterminada;
- tempos de deteção de 8000 e 2000 ms, calculados a partir das anotações sintéticas;
- latência média de 4100 ms na réplica e 4200 ms no produtor; duplicados e recuperação TCP excluídos;
- comparação exata em UTF-8, incluindo endereço/sensor com acentos e o custo de lotes vazios.

O código de teste também valida ficheiros incompletos/malformados e um `ultimoSeq` incoerente
com o corpo. A suite pode ser repetida com `testes/verificar.ps1`, conforme o guia de implementação.
