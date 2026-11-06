package rep04;

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
import java.util.function.Consumer;

// Cada subscrição tem o seu estado e o seu monitor; não há um lock comum às partições.
final class PartitionState {
    private final int replicaId;
    private final int partition;
    private final int n;
    private final RecordFile file;
    private final double loss;
    private final long discardSeq;
    private final HeartbeatMonitor heartbeat;
    private final Consumer<String> events;
    private final TreeMap<Long, SensorRecord> pending = new TreeMap<>();
    private long lastSeq;
    private String host;
    private int port;
    private long endpointInc = -1;
    private boolean discardedTarget;
    private long attempts;
    private long connections;
    private long tcpRecords;
    private long duplicates;
    private long discarded;

    PartitionState(int replicaId, int partition, int n, RecordFile file, double loss,
                   long discardSeq, Consumer<String> events)
            throws IOException {
        Wire.validateHeartbeatConfig();
        if (n < 1 || partition < 0 || partition >= n) {
            throw new IllegalArgumentException("Partição fora de N");
        }
        if (!Double.isFinite(loss) || loss < 0 || loss > 1) {
            throw new IllegalArgumentException("perda deve estar entre 0 e 1");
        }
        if (discardSeq != -1 && discardSeq < 1) {
            throw new IllegalArgumentException("descartar deve ser um seq positivo");
        }
        this.replicaId = replicaId;
        this.partition = partition;
        this.n = n;
        this.file = file;
        this.loss = loss;
        this.discardSeq = discardSeq;
        this.events = events;
        this.heartbeat = new HeartbeatMonitor(partition, Wire.PERIODO_HB_MS,
                Wire.FATOR_TIMEOUT, events);
        this.lastSeq = file.lastSeq();
    }

    // Executado pela própria thread de subscrição, incluindo a recuperação bloqueante.
    synchronized void receive(String message) {
        try {
            Batch batch = Batch.decode(message, n);
            for (SensorRecord record : batch.getRecords()) validateRecord(record);
            heartbeat.onHeartbeat(batch.getInc(), batch.getHb());
            events.accept(batch.receivedEvent(partition));
            // Um lote atrasado não substitui o contacto da incarnação mais recente.
            if (batch.getInc() >= endpointInc) {
                endpointInc = batch.getInc();
                host = batch.getAddress();
                port = batch.getPort();
            }
            // Ignorar um heartbeat antigo não significa descartar os seus registos.
            for (SensorRecord record : batch.getRecords()) {
                boolean discardFirst = record.getSeq() == discardSeq && !discardedTarget;
                if (discardFirst) discardedTarget = true;
                if (discardFirst || Math.random() < loss) {
                    discarded++;
                    log("DESCARTADO (simulação): " + record.getSeq());
                } else {
                    apply(record);
                }
            }
            // Inclui a perda do último registo, mesmo num lote sem registos.
            if (lastSeq < batch.getLastSeq()) {
                recover(batch.getLastSeq());
            }
        } catch (IllegalArgumentException e) {
            log("REJEITADO: " + e.getMessage());
        } catch (IOException e) {
            log("ESCRITA FALHOU: " + e.getMessage());
        } finally {
            printSummary();
        }
    }

    private SensorRecord parseRecord(String line) {
        return validateRecord(SensorRecord.fromLine(line));
    }

    private SensorRecord validateRecord(SensorRecord record) {
        VectorClock vector = record.getVector();
        if (vector.size() != n || vector.get(partition) != record.getSeq()
                || Math.floorMod(record.getSensorId().hashCode(), n) != partition) {
            throw new IllegalArgumentException("Vetor incompatível com p" + partition
                    + ": seq=" + record.getSeq() + " V=" + vector);
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

    private void recover(long to) {
        long from = lastSeq + 1;
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
                events.accept("RECUPERACAO;p" + partition + ";de=" + from + ";ate=" + to);
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

    // Não adquire o bloqueio de PartitionState: TCP/receção não atrasam a verificação.
    void checkHeartbeat() { heartbeat.check(); }
    HeartbeatMonitor.State heartbeatState() { return heartbeat.state(); }

    // Observação sem expor o TreeMap mutável; útil na verificação local sem Kubo.
    synchronized long lastSeq() { return lastSeq; }
    synchronized int retainedCount() { return pending.size(); }
    synchronized long recoveryAttempts() { return attempts; }
    synchronized long recoveryConnections() { return connections; }
    synchronized long recoveredRecords() { return tcpRecords; }
    synchronized long duplicateCount() { return duplicates; }
    synchronized long discardedCount() { return discarded; }
}
