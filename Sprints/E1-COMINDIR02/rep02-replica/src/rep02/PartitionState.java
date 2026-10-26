package rep02;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

// Cada subscrição tem o seu estado e o seu monitor; não há um lock comum às partições.
final class PartitionState {
    private final int replicaId;
    private final int partition;
    private final RecordFile file;
    private final double loss;
    private final long discardSeq;
    private final TreeMap<Long, SensorRecord> pending = new TreeMap<>();
    private long lastSeq;
    private String host;
    private int port;
    private boolean discardedTarget;
    private long attempts;
    private long connections;
    private long tcpRecords;
    private long duplicates;
    private long discarded;

    PartitionState(int replicaId, int partition, RecordFile file, double loss, long discardSeq)
            throws IOException {
        if (!Double.isFinite(loss) || loss < 0 || loss > 1) {
            throw new IllegalArgumentException("perda deve estar entre 0 e 1");
        }
        if (discardSeq != -1 && discardSeq < 1) {
            throw new IllegalArgumentException("descartar deve ser um seq positivo");
        }
        this.replicaId = replicaId;
        this.partition = partition;
        this.file = file;
        this.loss = loss;
        this.discardSeq = discardSeq;
        this.lastSeq = file.lastSeq();
    }

    // Executado pela própria thread de subscrição, incluindo a recuperação bloqueante.
    synchronized void receive(String message) {
        try {
            String[] fields = Wire.regParts(message);
            String nextHost = fields[0];
            int nextPort = Integer.parseInt(fields[1]);
            SensorRecord record = parseRecord(fields[2]);
            if (nextHost.isBlank() || nextPort < 1 || nextPort > 65535) {
                throw new IllegalArgumentException("Endereço ou porto inválido");
            }

            // Mesmo um duplicado ou um descarte simulado anuncia o contacto mais recente.
            host = nextHost;
            port = nextPort;
            boolean discardFirst = record.getSeq() == discardSeq && !discardedTarget;
            if (discardFirst) discardedTarget = true;
            if (discardFirst || Math.random() < loss) {
                discarded++;
                log("DESCARTADO (simulação): " + record.getSeq());
            } else {
                apply(record);
            }

            // No máximo um pedido por mensagem. Após falha, aguarda a receção seguinte.
            if (pending.size() > Wire.LIMITE_RETIDOS && pending.firstKey() > lastSeq + 1) {
                recover();
            }
        } catch (IllegalArgumentException e) {
            log("REJEITADO: " + message);
        } catch (IOException e) {
            log("ESCRITA FALHOU: " + e.getMessage());
        } finally {
            printSummary();
        }
    }

    private static SensorRecord parseRecord(String line) {
        if (line.split(";", -1).length != 4) {
            throw new IllegalArgumentException("Registo deve ter quatro campos: " + line);
        }
        SensorRecord record = SensorRecord.fromLine(line);
        if (record.getSeq() < 1 || record.getSensorId().isBlank()
                || !Double.isFinite(record.getTemperature()) || record.getTimestamp().isBlank()) {
            throw new IllegalArgumentException("Registo inválido: " + line);
        }
        return record;
    }

    private void apply(SensorRecord record) throws IOException {
        long seq = record.getSeq();
        if (seq <= lastSeq || pending.containsKey(seq)) {
            duplicates++;
            log("DUPLICADO: " + seq);
            return;
        }
        pending.put(seq, record);
        if (seq > lastSeq + 1) log("EM ESPERA: " + seq);
        drain();
    }

    private void drain() throws IOException {
        while (lastSeq < Long.MAX_VALUE) {
            SensorRecord next = pending.get(lastSeq + 1);
            if (next == null) return;
            // Se append falhar, o registo continua retido e lastSeq não avança.
            file.append(next);
            lastSeq = next.getSeq();
            pending.remove(lastSeq);
            System.out.println("ESCRITO: replica=" + replicaId + " p=" + partition
                    + " seq=" + lastSeq + " instanteMs=" + System.currentTimeMillis());
        }
    }

    private void recover() {
        long from = lastSeq + 1;
        long to = pending.firstKey() - 1;
        attempts++;
        log("RECUPERAÇÃO: " + from + ".." + to + " pedida a " + host + ":" + port);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Wire.T_TCP_MS);
            connections++;
            socket.setSoTimeout(Wire.T_TCP_MS);
            try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8))) {
                out.write("PEDIDO " + from + " " + to);
                out.newLine();
                out.flush();
                long expected = from;
                while (true) {
                    String line = in.readLine();
                    if (line == null) throw new EOFException("Resposta interrompida antes de FIM");
                    if (line.equals("FIM")) {
                        if (expected <= to) throw new IOException("FIM antes de receber o seq " + expected);
                        break;
                    }
                    if (line.equals("ERRO") || line.startsWith("ERRO ")) {
                        throw new IOException("Líder respondeu: " + line);
                    }
                    SensorRecord record = parseRecord(line);
                    if (record.getSeq() != expected || record.getSeq() > to) {
                        throw new IOException("Resposta fora do intervalo/ordem: seq=" + record.getSeq()
                                + ", esperado=" + expected + ", até=" + to);
                    }
                    expected++;
                    tcpRecords++;
                    log("RECUPERADO: " + record.getSeq());
                    // A simulação de perdas só existe em receive(), nunca nesta via.
                    apply(record);
                }
                drain();
            }
        } catch (IOException | IllegalArgumentException e) {
            log("RECUPERAÇÃO FALHOU: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    synchronized void printSummary() {
        System.out.println("ESTADO: replica=" + replicaId + " p=" + partition + " last=" + lastSeq
                + " retidos=" + pending.size() + " tentativasTCP=" + attempts
                + " ligacoesTCP=" + connections + " registosTCP=" + tcpRecords
                + " duplicados=" + duplicates + " descartados=" + discarded);
    }

    private void log(String message) {
        System.out.println("[replica=" + replicaId + " p=" + partition + "] " + message);
    }

    // Observação sem expor o TreeMap mutável; útil na verificação local sem Kubo.
    synchronized long lastSeq() { return lastSeq; }
    synchronized int retainedCount() { return pending.size(); }
    synchronized long recoveryAttempts() { return attempts; }
    synchronized long recoveryConnections() { return connections; }
    synchronized long recoveredRecords() { return tcpRecords; }
    synchronized long duplicateCount() { return duplicates; }
    synchronized long discardedCount() { return discarded; }
}
