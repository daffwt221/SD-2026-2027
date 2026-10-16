package rep01;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

// Agrupa os registos SIM em intervalos conhecidos, sem adicionar mensagens ao protocolo.
// O resumo por inatividade é uma observação parcial, não uma confirmação de entrega.
public class BurstMetrics {
    private final int size;
    private final long firstSeq;
    private final Map<Long, Batch> batches = new TreeMap<>();

    private static class Batch {
        long firstReceived;
        long lastReceived;
        long received;
        long written;
        long duplicates;
        boolean changed;
        final Set<Long> unique = new HashSet<>();
    }

    public BurstMetrics(int size, long firstSeq) {
        if (size <= 0 || firstSeq <= 0) throw new IllegalArgumentException("Tamanho e seq inicial devem ser positivos.");
        this.size = size;
        this.firstSeq = firstSeq;
    }

    private Batch batch(SensorRecord record) {
        if (!record.getSensorId().equals("SIM") || record.getSeq() < firstSeq) return null;
        long index = (record.getSeq() - firstSeq) / size;
        return batches.computeIfAbsent(index, ignored -> new Batch());
    }

    public void received(SensorRecord record, long at) {
        Batch batch = batch(record);
        if (batch == null) return;
        if (batch.received == 0) batch.firstReceived = at;
        batch.lastReceived = at;
        batch.received++;
        batch.unique.add(record.getSeq());
        batch.changed = true;
    }

    public void written(SensorRecord record) {
        Batch batch = batch(record);
        if (batch != null) {
            batch.written++;
            batch.changed = true;
        }
    }

    public void duplicate(SensorRecord record) {
        Batch batch = batch(record);
        if (batch != null) {
            batch.duplicates++;
            batch.changed = true;
        }
    }

    public void report(TreeMap<Long, SensorRecord> pending) {
        for (Map.Entry<Long, Batch> entry : batches.entrySet()) {
            Batch batch = entry.getValue();
            if (!batch.changed) continue;
            long start = firstSeq + entry.getKey() * size;
            long end = start + size - 1;
            long retained = pending.subMap(start, true, end, true).values().stream()
                    .filter(record -> record.getSensorId().equals("SIM")).count();
            System.out.printf("RAJADA (parcial) seq=%d..%d primeiraRececao=%d ultimaRececao=%d recebidos=%d unicos=%d escritos=%d retidos=%d duplicados=%d naoRecebidos=%d%n",
                    start, end, batch.firstReceived, batch.lastReceived, batch.received,
                    batch.unique.size(), batch.written, retained, batch.duplicates, size - batch.unique.size());
            batch.changed = false;
        }
    }
}
