# Validação dos itens 1–5

Execução local de 6 de novembro de 2026. JDK 26.0.1 com compilação `--release 11`.
O teste terminou com `PASS: 58 verificações` e ambos os verificadores terminaram com código 0.

Foram executados três líderes, três réplicas e um processador em processos Java separados.
Uma API HTTP local simulou Pub/Sub e uma publicação devolveu deliberadamente HTTP 500.
O período foi reduzido para 1000 ms e o fator do timeout para 2 em todos esses processos;
o período de verificação manteve-se em 1000 ms. Os valores por omissão do produto são 5000/3/1000.

A validação incluiu recuperação do último registo após uma publicação falhada e recuperação
de um registo que ficou pendente antes de o líder ser terminado à força. Após o reinício,
as réplicas registaram `REINICIO`, `RETOMA` e `RECUPERACAO` e convergiram com o líder.
Também foi observado um aviso no processador para um destino suspeito, mantendo a tentativa
de publicação do alarme.

| Verificação final | Resultado |
|---|---|
| Registos nos líderes p0/p1/p2 | 4 / 2 / 1 |
| Cópias por partição | 2, ambas iguais ao líder |
| `PartitionCheck` | válido e convergente |
| `CausalCheck` | 7 eventos, 0 erros Lamport |

Os ficheiros de dados, heartbeats, monitores e os dois relatórios estão nesta pasta.
O código reproduzível do teste está em `../../testes/Rep04SmokeTest.java` e o comando em
`../../testes/verificar.ps1`.

Esta evidência é de uma API simulada. Não constitui os ensaios do ponto 8 nem uma medição
do desempenho ou da fiabilidade do IPFS real.
