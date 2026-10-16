package rep01;

import java.io.IOException;
import java.util.TreeMap;

// Estado comum aos dois transportes: ficheiro em sequência e pendentes em memória.
public class ReplicaState {
    private final RecordFile file;
    private final TreeMap<Long, SensorRecord> pending = new TreeMap<>();
    private final BurstMetrics metrics;
    private long last;
    private long lastReceivedAt;

    public ReplicaState(String id, int burstSize, Long firstBurstSeq) throws IOException {
        file = new RecordFile("replica-" + id + ".txt");
        last = file.lastSeq();
        long start = firstBurstSeq == null ? last + 1 : firstBurstSeq;
        metrics = new BurstMetrics(burstSize, start);
        System.out.println("Medição: rajadas de " + burstSize + " registos, a partir de seq " + start);
    }

    public synchronized void accept(String line, long receivedAt) throws IOException {
        SensorRecord record;
        try {
            record = SensorRecord.fromLine(line);
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + line);
            return;
        }
        lastReceivedAt = receivedAt;
        long seq = record.getSeq();
        metrics.received(record, receivedAt);
        if (seq <= last || pending.containsKey(seq)) {
            metrics.duplicate(record);
            System.out.println("DUPLICADO: " + seq);
            return;
        }
        if (seq > last + 1) {
            pending.put(seq, record);
            System.out.println("EM ESPERA: " + seq + " (falta o registo " + (last + 1) + ")");
            return;
        }
        append(record);
        while (pending.containsKey(last + 1)) {
            SensorRecord next = pending.get(last + 1);
            append(next);
            pending.remove(last);
        }
    }

    private void append(SensorRecord record) throws IOException {
        file.append(record);
        last = record.getSeq();
        metrics.written(record);
        System.out.println("Aplicado: " + record.toLine());
    }

    public synchronized void reportIfIdle() {
        if (System.currentTimeMillis() - lastReceivedAt >= 1000) metrics.report(pending);
    }
}
