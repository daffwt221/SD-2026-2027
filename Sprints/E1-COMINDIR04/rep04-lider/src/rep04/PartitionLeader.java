package rep04;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Um líder atribui sequências, persiste e serve a recuperação da sua partição. */
public final class PartitionLeader {
    private final int partition;
    private final Partitioner partitioner;
    private final String host;
    private final int port;
    private final IpfsPubSub pubsub;
    private final RecordFile file;
    private final long inc;

    // Todas as leituras e escritas destes campos usam o monitor de file.
    private long last;
    private long lamport;
    private VectorClock vector;
    private long written;
    private long firstWriteMs;
    private long lastWriteMs;
    private long hb;
    private List<SensorRecord> pendentes = new ArrayList<>();

    PartitionLeader(int partition, int count, String host, String api) throws IOException {
        Wire.validateHeartbeatConfig();
        this.inc = System.currentTimeMillis();
        this.partitioner = new Partitioner(count);
        if (partition < 0 || partition >= count || partition > 65535 - Wire.PORTO_BASE) {
            throw new IllegalArgumentException("Partição inválida: " + partition);
        }
        if (host == null || host.isBlank() || host.indexOf(';') >= 0
                || host.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new IllegalArgumentException("Endereço anunciado inválido");
        }
        URI endpoint = URI.create(api);
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null) {
            throw new IllegalArgumentException("Endereço da API inválido: " + api);
        }
        this.partition = partition;
        this.host = host;
        this.port = Wire.PORTO_BASE + partition;
        this.pubsub = new IpfsPubSub(api);
        this.file = new RecordFile("lider-p" + partition + ".txt");
        synchronized (file) {
            SensorRecord previous = file.last();
            if (previous == null) {
                last = 0;
                lamport = 0;
                vector = new VectorClock(count);
            } else {
                last = previous.getSeq();
                lamport = previous.getLamport();
                vector = previous.getVector();
            }
            if (vector.size() != count || vector.get(partition) != last) {
                throw new IllegalArgumentException("Histórico causal incompatível com p"
                        + partition + ": seq=" + last + " V=" + vector + " N=" + count);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 4) {
            usage();
            return;
        }
        try {
            int partition = Integer.parseInt(args[0]);
            int count = Integer.parseInt(args[1]);
            String host = args.length >= 3 ? args[2] : "127.0.0.1";
            String api = args.length >= 4 ? args[3] : "http://127.0.0.1:5001/api/v0";
            new PartitionLeader(partition, count, host, api).run();
        } catch (IllegalArgumentException e) {
            System.err.println("Configuração inválida: " + e.getMessage());
            usage();
        } catch (IOException e) {
            System.err.println("Não foi possível executar o líder: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    private static void usage() {
        System.err.println("Uso: PartitionLeader <k> <N> [endereço] [api]");
    }

    private void run() throws IOException {
        // Reserva o porto antes de iniciar a entrada; um conflito não deixa uma thread órfã.
        try (RecoveryServer recovery = new RecoveryServer(partition, port, host, file, () -> last)) {
            PartitionInput input = new PartitionInput(partition, partitioner, pubsub, this::acceptReading);
            Thread inputThread = new Thread(input, "entrada-p" + partition);
            inputThread.setDaemon(true);
            Runtime.getRuntime().addShutdownHook(new Thread(this::printMetrics, "metricas-p" + partition));
            inputThread.start();
            ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(
                    task -> new Thread(task, "heartbeat-p" + partition));
            try {
                heartbeats.scheduleAtFixedRate(this::enviarLote, 0,
                        Wire.PERIODO_HB_MS, TimeUnit.MILLISECONDS);
                recovery.run(); // A recuperação TCP mantém-se na thread principal.
            } finally {
                heartbeats.shutdownNow();
                inputThread.interrupt();
            }
        }
    }

    void acceptReading(String sensor, double temperature,
                       long receivedLamport, VectorClock receivedVector) {
        SensorRecord record;
        try {
            // Relógios + seq + append usam o mesmo monitor que a recuperação TCP.
            synchronized (file) {
                long nextLamport = Math.addExact(Math.max(lamport, receivedLamport), 1);
                VectorClock nextVector = vector.copy();
                nextVector.merge(receivedVector);
                nextVector.increment(partition);
                long next = Math.addExact(last, 1);
                if (nextVector.get(partition) != next) {
                    throw new IllegalArgumentException("V[" + partition + "]="
                            + nextVector.get(partition) + " difere de seq=" + next);
                }
                record = SensorRecord.now(next, sensor, temperature, nextLamport, nextVector);
                file.append(record);
                // Só confirma o estado após persistir; uma rejeição não avança os relógios.
                lamport = nextLamport;
                vector = nextVector;
                last = next;
                pendentes.add(record);
                written++;
                lastWriteMs = System.currentTimeMillis();
                if (written == 1) firstWriteMs = lastWriteMs;
                System.out.println("ESCRITO: p=" + partition + " seq=" + last
                        + " instanteMs=" + lastWriteMs + " total=" + written);
                System.out.println("EVENTO: seq=" + last + " L=" + lamport + " V=" + vector);
            }
        } catch (IOException | ArithmeticException | IllegalArgumentException e) {
            System.out.println("ESCRITA FALHOU: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

    }

    void enviarLote() {
        List<SensorRecord> records;
        long ultimoSeq;
        long numeroHb;
        // O mesmo bloqueio protege seq/append e o fecho do lote: o cabeçalho e o corpo coincidem.
        synchronized (file) {
            records = pendentes;
            pendentes = new ArrayList<>();
            ultimoSeq = last;
            numeroHb = ++hb;
        }
        // Codificação, publicação e registo do heartbeat não bloqueiam novas leituras.
        try {
            Batch batch = new Batch(host, port, inc, numeroHb, ultimoSeq, records);
            String message = batch.encode();
            long instant = System.currentTimeMillis();
            pubsub.publish(Wire.topicData(partition), message);
            System.out.println("HEARTBEAT: hb=" + numeroHb + " n=" + records.size()
                    + " ultimoSeq=" + ultimoSeq);
            String line = inc + ";" + numeroHb + ";" + instant + ";" + ultimoSeq
                    + ";" + records.size() + ";" + message.getBytes(StandardCharsets.UTF_8).length;
            Files.writeString(Path.of("hb-p" + partition + ".txt"),
                    line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("PUBLICAÇÃO INTERROMPIDA: hb=" + numeroHb);
        } catch (IOException | IllegalArgumentException e) {
            // Não reinsere os registos em pendentes: continuam disponíveis no ficheiro para TCP.
            System.out.println("HEARTBEAT FALHOU: hb=" + numeroHb + " " + e.getMessage());
        }
    }

    private void printMetrics() {
        synchronized (file) {
            System.out.println("MÉTRICAS LÍDER: p=" + partition + " escritos=" + written
                    + " primeiroMs=" + firstWriteMs + " ultimoMs=" + lastWriteMs + " ultimoSeq=" + last);
        }
    }
}
