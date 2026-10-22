package rep02;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("Líder p" + partition + ": recuperação TCP no porto " + port
                    + ", endereço anunciado " + host + ", último=" + last);
            Thread input = new Thread(this::subscribe, "entrada-p" + partition);
            input.setDaemon(true);
            Runtime.getRuntime().addShutdownHook(new Thread(this::printMetrics, "metricas-p" + partition));
            input.start();

            while (true) {
                // accept bloqueia; só a thread principal atende as recuperações, uma de cada vez.
                try (Socket socket = server.accept()) {
                    handleRecovery(socket);
                } catch (IOException | IllegalArgumentException e) {
                    System.out.println("Recuperação interrompida: " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                }
            }
        }
    }

    private void subscribe() {
        try {
            // A subscrição bloqueia entre mensagens; a publicação também espera pela API HTTP.
            pubsub.subscribe(Wire.topicIn(partition), this::acceptMessage);
            System.out.println("Subscrição terminada: " + Wire.topicIn(partition));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Subscrição interrompida: " + Wire.topicIn(partition));
        } catch (IOException | RuntimeException e) {
            System.out.println("Subscrição falhou: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    void acceptMessage(String message) {
        String sensor;
        double temperature;
        try {
            String[] fields = message.split(";", -1);
            if (fields.length != 3 || !fields[0].equals("IN") || fields[1].isBlank()
                    || fields[1].codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                    || !fields[2].equals(fields[2].trim())) {
                throw new IllegalArgumentException("IN mal formado");
            }
            sensor = fields[1];
            temperature = Double.parseDouble(fields[2]);
            if (!Double.isFinite(temperature)) {
                throw new IllegalArgumentException("Temperatura não finita");
            }
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + message);
            return;
        }

        int expectedPartition = partitioner.of(sensor);
        if (expectedPartition != partition) {
            System.out.println("PARTIÇÃO ERRADA: " + sensor + " pertence a p" + expectedPartition);
            return;
        }

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

    void handleRecovery(Socket socket) throws IOException {
        socket.setSoTimeout(Wire.T_TCP_MS);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

        String request = in.readLine(); // Espera pelo pedido, no máximo T_TCP_MS.
        long from;
        long to;
        try {
            if (request == null) throw new IllegalArgumentException("pedido ausente");
            String[] fields = request.trim().split("\\s+");
            if (fields.length != 3 || !fields[0].equals("PEDIDO")) {
                throw new IllegalArgumentException("formato esperado: PEDIDO <de> <até>");
            }
            from = Long.parseLong(fields[1]);
            to = Long.parseLong(fields[2]);
            if (from < 1 || to < 1 || from > to) {
                throw new IllegalArgumentException("intervalo inválido");
            }
        } catch (IllegalArgumentException e) {
            out.write("ERRO " + e.getMessage());
            out.newLine();
            out.flush();
            return;
        }

        List<SensorRecord> records;
        synchronized (file) {
            to = Math.min(to, last);
            records = file.range(from, to);
        }
        // O snapshot já está em memória: uma réplica lenta não prende o monitor do ficheiro.
        for (SensorRecord record : records) {
            out.write(record.toLine());
            out.newLine();
        }
        out.write("FIM");
        out.newLine();
        out.flush();
        System.out.println("RECUPERAÇÃO: " + from + ".." + to + " para "
                + socket.getRemoteSocketAddress() + " (" + records.size() + " registos)");
    }

    private void printMetrics() {
        synchronized (file) {
            System.out.println("MÉTRICAS LÍDER: p=" + partition + " escritos=" + written
                    + " primeiroMs=" + firstWriteMs + " ultimoMs=" + lastWriteMs + " ultimoSeq=" + last);
        }
    }
}
