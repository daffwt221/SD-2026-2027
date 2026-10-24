package rep02;

import java.io.IOException;
import java.net.URI;

/** Um líder atribui sequências, persiste e serve a recuperação da sua partição. */
public final class PartitionLeader {
    private final int partition;
    private final Partitioner partitioner;
    private final String host;
    private final int port;
    private final IpfsPubSub pubsub;
    private final RecordFile file;

    // Todas as leituras e escritas destes campos usam o monitor de file.
    private long last;
    private long written;
    private long firstWriteMs;
    private long lastWriteMs;

    PartitionLeader(int partition, int count, String host, String api) throws IOException {
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
            last = file.lastSeq();
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

            recovery.run(); // Chamada direta: a recuperação continua na thread principal.
        }
    }

    void acceptReading(String sensor, double temperature) {
        SensorRecord record;
        try {
            // seq + append são uma só operação; o servidor TCP usa exatamente o mesmo monitor.
            synchronized (file) {
                long next = Math.addExact(last, 1);
                record = SensorRecord.now(next, sensor, temperature);
                file.append(record);
                last = next;
                written++;
                lastWriteMs = System.currentTimeMillis();
                if (written == 1) firstWriteMs = lastWriteMs;
                System.out.println("ESCRITO: p=" + partition + " seq=" + last
                        + " instanteMs=" + lastWriteMs + " total=" + written);
            }
        } catch (IOException | ArithmeticException e) {
            System.out.println("ESCRITA FALHOU: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        // A chamada de rede decorre fora do monitor; o registo já existe para recuperação.
        try {
            pubsub.publish(Wire.topicData(partition), Wire.reg(host, port, record));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("PUBLICAÇÃO INTERROMPIDA: " + record.getSeq());
        } catch (IOException | IllegalArgumentException e) {
            System.out.println("PUBLICAÇÃO FALHOU: " + record.getSeq() + " " + e.getMessage());
        }
    }

    private void printMetrics() {
        synchronized (file) {
            System.out.println("MÉTRICAS LÍDER: p=" + partition + " escritos=" + written
                    + " primeiroMs=" + firstWriteMs + " ultimoMs=" + lastWriteMs + " ultimoSeq=" + last);
        }
    }
}
