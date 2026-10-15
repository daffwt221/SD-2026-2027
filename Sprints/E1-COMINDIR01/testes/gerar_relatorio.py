"""Create a plain-text report from an actual successful integration run."""
import json
from pathlib import Path
from statistics import mean
import sys

root = Path(__file__).resolve().parents[1]
if len(sys.argv) > 1:
    source = Path(sys.argv[1])
else:
    candidates = sorted((root / "resultados").glob("*/resultados.json"), reverse=True)
    source = next(path for path in candidates
                  if json.loads(path.read_text(encoding="utf-8")).get("status") == "PASS")
data = json.loads(source.read_text(encoding="utf-8"))
assert data["status"] == "PASS", "Report requires a successful test run"

lines = [
    "COMINDIR01 — REGISTO TÉCNICO E RESULTADOS",
    "",
    "Relatório gerado a partir de testes executados por assistência automatizada.",
    "Não substitui a explicação oral do estudante nem demonstra testes entre cidades.",
    "Origem dos dados: " + str(source.relative_to(root)),
    "Versão: " + data["kubo"],
    data["java"].strip(),
    "Todos os processos correram nesta máquina Windows; APIs e swarm em loopback.",
    "Grupo multicast dos testes: " + data["multicast_group"] + ":" + str(data["multicast_port"]),
    "O código Java foi compilado com --release 11. Não usa bibliotecas externas.",
    "",
    "PONTOS 1–13 — IMPLEMENTAÇÃO E VERIFICAÇÃO",
    "",
    "SensorRecord define o formato seq;sensor;temperatura;instante; RecordFile faz append UTF-8.",
    "O líder recupera lastSeq, atribui números crescentes, guarda localmente e depois propaga.",
    "MulticastLeader envia um datagrama por registo, com TTL 1, sem lista de réplicas.",
    "PubSubLeader conserva a mesma CLI, ficheiro, numeração e comando rajada; muda o transporte.",
    "A API HTTP do Kubo recebe POST multipart; tópicos e dados recebidos usam multibase base64url.",
    "ReplicaState é comum aos dois transportes: só escreve last+1; ignora duplicados; retém os demais.",
    "O TreeMap contém os registos fora de ordem. Só após append bem-sucedido se atualiza last.",
    "Mensagens inválidas são rejeitadas; falhas reais de disco não são confundidas com erro de formato.",
    "Rajada n gera n registos SIM, com temperaturas em [15,30), sem pausas entre os envios.",
    "O modo manual é só para injetar datagramas/mensagens de teste e não escreve lider.txt.",
    "",
    "OPERAÇÕES BLOQUEANTES",
    "Scanner.hasNextLine: espera por uma linha ou fim da entrada.",
    "RecordFile.lastSeq/append: I/O de disco; synchronized pode também esperar pelo monitor do objeto.",
    "MulticastSocket.receive: espera por datagrama ou timeout de 1 s para apresentar medições.",
    "MulticastSocket.send: chamada de I/O local; sucesso não é confirmação das réplicas.",
    "HttpClient.send: espera pela resposta HTTP local do daemon.",
    "IpfsPubSub.subscribe: leitura do stream HTTP bloqueia entre mensagens; não é uma lista finita.",
    "",
    "VERIFICAÇÕES AUTOMÁTICAS",
]
lines += ["- " + check for check in data["checks"]]
lines += [
    "- Três rajadas de 1000, em cada um de três cenários, com três réplicas.",
    "- Contadores escritos confrontados com o conteúdo dos ficheiros.",
    "- Ficheiros das réplicas verificados como prefixos sem saltos do ficheiro do líder.",
    "- Nós ligados: swarm connect executado e pubsub peers confirmou o interesse no tópico.",
    "- ipfs.ps1: Init, Start nos dois nós, Connect e Status verificados separadamente.",
    "",
    "PONTO 14 — FALHAS OBSERVADAS",
    "",
]
names = {
    "leader starts before replicas": "Líder envia três registos antes de arrancar a réplica",
    "wrong multicast group": "Réplica com grupo multicast diferente",
    "receive buffer 10 bytes": "Buffer de receção reduzido a 10 bytes",
    "stop replica during burst": "Parar réplica a meio de rajada 1000 e reiniciar",
    "wrong topic": "Réplica subscreve rep01-temp",
    "Pubsub.Enabled false": "PubSub desativado no nó da réplica",
    "two nodes disconnected": "Dois nós sem ligação, sem bootstrap e sem mDNS",
    "leader daemon stopped": "Daemon do líder parado",
}
for fault in data["faults"]:
    lines.extend([
        names.get(fault["name"], fault["name"]) + " | " + fault["mechanism"] + " | lado: " + fault["side"],
        "  Observação real: " + fault["observation"].replace("\n", " / "),
        "  Evidência: " + str(source.parent.relative_to(root) / fault["scenario"]),
    ])
lines += [
    "",
    "Os testes usam um grupo equivalente isolado (239.255.71.99), e o grupo errado 239.255.71.100,",
    "em vez de alterar os endereços das sessões normais do estudante. Os valores por omissão",
    "da aplicação continuam a ser 230.0.0.1:6789.",
    "O buffer pequeno pode truncar sem erro do socket: a interpretação é que rejeita o conteúdo.",
    "Nenhuma observação de perdas permite, sozinha, localizar a perda na rede, emissor ou recetor.",
    "",
    "PONTO 15 — CONSTRUÇÕES E GARANTIAS",
    "",
    "MulticastSocket/joinGroup — MulticastReplica.main: adesão ao grupo e receção um-para-muitos;",
    "  não garante entrega, ordem, ausência de duplicados nem recuperação.",
    "TTL 1 — MulticastLeader.main: limita o alcance de encaminhamento; não confirma entrega.",
    "Append antes de propagar — ambos os líderes: o registo propagado já existe no líder;",
    "  pode haver um registo local que nunca chegou a ser enviado.",
    "Seq — ReplicaState.accept: deteta duplicados e lacunas; só permite append em sequência;",
    "  não recupera perdas nem autentica o emissor.",
    "toLine/fromLine — SensorRecord: formato textual comum a ficheiro e rede;",
    "  não prova integridade/autoria e a validação fornecida não verifica o formato do timestamp.",
    "Tópico — PubSubLeader/PubSubReplica.TOPIC: seleciona o interesse dos subscritores;",
    "  não é histórico, autorização ou confirmação.",
    "Daemon/API HTTP — IpfsPubSub: fornece publicação/subscrição no nó; requer processo local",
    "  disponível e ligações entre nós; HTTP 200 não confirma escrita no ficheiro remoto.",
    "Multibase — IpfsPubSub: codificação exigida pela RPC para tópico e data;",
    "  não é cifragem nem autenticação. A CLI Kubo faz a codificação automaticamente.",
    "",
    "PONTO 16 — MÉDIAS DAS TRÊS RAJADAS DE 1000",
    "",
    "Cenário | intervalo envio ms | duração total ms | débito reg/s | latência réplica mais lenta ms",
]
scenarios = ["multicast", "pubsub-um-no", "pubsub-dois-nos"]
summary = {}
for scenario in scenarios:
    rows = [row for row in data["measurements"] if row["scenario"] == scenario]
    leaders = [next(row["leader"] for row in rows if row["run"] == run) for run in (1, 2, 3)]
    latency = mean(max(row["latency_ms"] for row in rows if row["run"] == run) for run in (1, 2, 3))
    throughput = mean(float(leader["debitoRegistosS"]) for leader in leaders)
    summary[scenario] = {"latency": latency, "throughput": throughput}
    lines.append(f"{scenario} | {mean(int(x['intervaloEnvioMs']) for x in leaders):.2f} | "
                 f"{mean(float(x['duracaoMs']) for x in leaders):.2f} | {throughput:.2f} | {latency:.2f}")
    for replica in (1, 2, 3):
        observations = [row["replica_metrics"] for row in rows if row["replica"] == replica]
        lines.append(f"  Réplica {replica}: médias não recebidos="
                     f"{mean(int(x['naoRecebidos']) for x in observations):.2f}; duplicados="
                     f"{mean(int(x['duplicados']) for x in observations):.2f}; retidos="
                     f"{mean(int(x['retidos']) for x in observations):.2f}.")
lines += [
    "",
    "Componentes além da JVM: multicast=0; PubSub um nó=1 daemon; PubSub dois nós=2 daemons.",
    "Configuração com ipfs.ps1, sem contar compilação/arranque Java ou instalação: multicast=0;",
    "  um nó=2 comandos (Init, Start); dois nós=5 (Init e Start por nó, Connect).",
    "Réplica atrasada: nos três cenários, não obtém o passado e retém o futuro após a primeira lacuna.",
    "Redes diferentes: multicast depende de suporte/configuração dos routers; IPFS usa ligações",
    "  normais entre nós, mas exige conectividade real e descoberta/ligação de pares.",
    "As configurações locais deste ensaio só escutam loopback: não demonstram ligação entre cidades.",
    "",
    "MÉTODO E LIMITAÇÕES DAS MEDIÇÕES",
    "Intervalo de envio: ultimoEnvio - primeiroEnvio; ambos em currentTimeMillis antes de send/publish.",
    "Duração total: nanoTime, incluindo geração e append local; débito = n / duração total.",
    "Latência: ultimaRececao - primeiroEnvio; réplicas e líder partilham o relógio desta máquina.",
    "As marcas de receção medem a entrega à aplicação Java, incluindo eventuais filas de processamento.",
    "A comparação inclui escrita de disco e mensagens de consola, não mede apenas o transporte.",
    "Cada rajada arranca um processo líder novo; cold start/primeira ligação HTTP afetam os resultados.",
    "Os intervalos SIM são configurados pelo tamanho e primeiro seq. Não misturar leituras comuns.",
    "Resumo após 1 s sem dados é parcial, não um protocolo de fim; mensagens tardias podem alterá-lo.",
    "Uma rajada totalmente perdida não cria resumo. Não recebidos é inferido de n configurado,",
    "não distingue perda definitiva de atraso. Não existem ACK, retransmissão ou transferência de estado.",
    "",
    "PONTO 17 — COMPARAÇÃO E LIMITES",
    "",
]
fastest = min(summary, key=lambda key: summary[key]["latency"])
highest = max(summary, key=lambda key: summary[key]["throughput"])
lines += [
    f"Desempenho: menor latência média observada em {fastest}; maior débito em {highest}.",
    "  Multicast faz uma chamada UDP. PubSub acrescenta pedidos HTTP, processamento do daemon",
    "  e, com dois nós, gossip/encaminhamento e entrega num segundo stream HTTP.",
    "  Isto é uma explicação plausível do custo, não uma decomposição medida de cada etapa.",
    "Escalabilidade: de 3 para 300 réplicas, o líder multicast continua a enviar um datagrama",
    "  por registo. A rede replica nos ramos necessários; tráfego de entrega cresce com os destinos.",
    "  Em PubSub cada nó mantém uma malha limitada de vizinhos e encaminha por gossip;",
    "  mais nós implicam mais ligações e tráfego agregado, não 300 envios diretos do líder.",
    "  A distribuição real de carga depende da topologia. Este ensaio não mediu 300 réplicas.",
    "Fiabilidade: seq deteta lacunas e impede escrita fora de ordem; não demonstra a causa de uma",
    "  perda. Uma execução sem perdas não torna fiável nenhum dos transportes.",
    "Recuperação: com uma lacuna permanente, o ficheiro fica parado e os pendentes crescem.",
    "  Ao reiniciar a réplica, perde-se a memória dos pendentes. Seria necessário obter o histórico",
    "  em falta ou um estado atualizado; ambos ficam fora do âmbito desta ficha.",
    "Dependências e alcance: num edifício com multicast permitido, evita-se o daemon e a configuração",
    "  de pares. Para várias cidades, PubSub oferece propagação sobre ligações IP normais;",
    "  continua a depender de nós alcançáveis, configuração e disponibilidade.",
    "  As medidas locais apoiam apenas a comparação de custo nesta máquina, não a latência WAN.",
    "Ponto único de falha: sem líder não há novas escritas; as réplicas continuam à escuta.",
    "  Nenhum dos transportes elege outro líder. Dois escritores poderiam reutilizar seq e gerar",
    "  históricos incompatíveis. Coordenação de escritores/eleição está fora do âmbito.",
    "Segurança: o formato e seq não autenticam o emissor. Um registo forjado bem formado pode",
    "  ocupar um seq ou fazer crescer os pendentes. O from do IPFS identifica o nó publicador,",
    "  mas só ajuda se for comparado com uma identidade de líder confiável. Essa verificação",
    "  teria de acontecer na receção, antes de ReplicaState.accept/append. Não está implementada.",
    "  O extrator atual usa apenas data; não deve ser apresentado como autenticação.",
    "",
    "CRITÉRIOS DE ACEITAÇÃO — MAPA PARA REVISÃO",
    "CA1: líderes, SensorRecord, RecordFile; ordem local/envio, reinício, pontos bloqueantes.",
    "CA2: MulticastLeader/Replica; cenários multicast e falhas de grupo/buffer/interrupção.",
    "CA3: IpfsPubSub e PubSubLeader/Replica; CLI, cenários de um/dois nós e peers confirmados.",
    "CA4: diretórios de falhas com logs e ficheiros; distinguir rejeição, exceção e divergência silenciosa.",
    "CA5: médias acima e limites explícitos; apresentar as decisões por palavras próprias.",
    "O resultado técnico não substitui a demonstração e compreensão exigidas pela avaliação oral.",
    "",
    "REFERÊNCIAS",
    "https://github.com/cunhaestgv/SD-2026-2027/issues/14",
    "https://docs.ipfs.tech/reference/kubo/rpc/",
    "https://github.com/ipfs/kubo/releases/tag/v0.43.1",
]
destination = root / "REGISTO.txt"
destination.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(destination)
