package raft04;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Observa lotes por termo, deduplica (seq, termo do registo) e publica alarmes como IN. */
public final class AlarmProcessor implements AutoCloseable {
    private final Partitioner partitioner;
    private final IpfsPubSub pubsub;
    private final Object monitor = new Object();
    private final List<HeartbeatMonitor> heartbeats = new ArrayList<>();
    private final List<Set<String>> seen = new ArrayList<>();
    private final List<Thread> subscriptions = new ArrayList<>();
    private final MonitorLog events = new MonitorLog("monitor-processador.txt");
    private final long[] highestTerm;
    private final VectorClock vector;
    private final ScheduledExecutorService verification;
    private long lamport;
    private volatile boolean closed;

    AlarmProcessor(int n, String api) {
        Wire.validateHeartbeatConfig();
        partitioner = new Partitioner(n);
        pubsub = new IpfsPubSub(api);
        highestTerm = new long[n];
        Arrays.fill(highestTerm, -1);
        vector = new VectorClock(n);
        for (int k = 0; k < n; k++) {
            seen.add(new HashSet<>());
            heartbeats.add(new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, events));
        }
        verification = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "verificacao-processador");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Uso: AlarmProcessor <N> [api]");
            System.exit(1);
        }
        try (AlarmProcessor processor = new AlarmProcessor(Integer.parseInt(args[0]),
                args.length == 2 ? args[1] : "http://127.0.0.1:5001/api/v0")) {
            Runtime.getRuntime().addShutdownHook(new Thread(processor::close, "fecho-processador"));
            processor.run();
        } catch (IllegalArgumentException e) {
            System.err.println("ERRO: " + e.getMessage());
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void run() throws InterruptedException {
        verification.scheduleAtFixedRate(() -> {
            for (HeartbeatMonitor heartbeat : heartbeats) heartbeat.check();
        }, Wire.VERIFICACAO_MS, Wire.VERIFICACAO_MS, TimeUnit.MILLISECONDS);
        for (int k = 0; k < partitioner.count(); k++) {
            final int partition = k;
            Thread thread = new Thread(() -> subscribe(partition), "processador-p" + k);
            subscriptions.add(thread);
            thread.start();
        }
        for (Thread thread : subscriptions) thread.join();
    }

    private void subscribe(int partition) {
        while (!closed) {
            try {
                pubsub.subscribe(Wire.topicData(partition), message -> receive(partition, message));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                if (!closed) events.accept("ERRO;p" + partition + ";origem=subscricao;detalhe=" + e.getMessage());
            }
            if (!closed) try { Thread.sleep(250); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }

    void receive(int partition, String message) {
        try {
            if (partition < 0 || partition >= partitioner.count()) throw new IllegalArgumentException("partição inválida");
            Batch batch = Batch.decode(message, partitioner.count());
            for (SensorRecord record : batch.getRecords()) {
                if (partitioner.of(record.getSensorId()) != partition
                        || record.getVector().get(partition) != record.getSeq())
                    throw new IllegalArgumentException("registo incompatível com p" + partition);
            }
            synchronized (monitor) {
                if (batch.getTerm() < highestTerm[partition]) {
                    events.accept("IGNORADO;p" + partition + ";origem=LOTE;termoMsg="
                            + batch.getTerm() + ";atual=" + highestTerm[partition]);
                    return;
                }
                highestTerm[partition] = batch.getTerm();
            }
            heartbeats.get(partition).onHeartbeat(batch.getTerm(), batch.getLeader(), batch.getHb());
            events.accept(batch.receivedEvent(partition));
            for (SensorRecord record : batch.getRecords()) processRecord(partition, batch.getTerm(), record);
        } catch (IllegalArgumentException e) {
            events.accept("ERRO;p" + partition + ";origem=LOTE;detalhe=" + e.getMessage());
        }
    }

    private void processRecord(int partition, long batchTerm, SensorRecord record) {
        String sensor;
        int destination;
        long alarmLamport;
        VectorClock alarmVector;
        synchronized (monitor) {
            if (batchTerm < highestTerm[partition]) return;
            String identity = record.getSeq() + ":" + record.getTerm();
            if (!seen.get(partition).add(identity)) return;
            if (record.getSensorId().startsWith("ALARM-")) return;
            lamport = Math.max(lamport, record.getLamport());
            vector.merge(record.getVector());
            events.accept("OBSERVADO;p" + partition + ";seq=" + record.getSeq() + ";termo="
                    + record.getTerm() + ";L=" + lamport + ";V=" + vector);
            if (record.getTemperature() < Wire.LIMIAR_ALARME) return;
            sensor = "ALARM-" + record.getSensorId();
            destination = partitioner.of(sensor);
            alarmLamport = lamport;
            alarmVector = vector.copy();
        }
        if (heartbeats.get(destination).state() == HeartbeatMonitor.State.SUSPEITO)
            events.accept("AVISO;p" + destination + ";AVISO: partição p" + destination
                    + " sem líder conhecido; alarme " + sensor + " pode perder-se");
        try {
            pubsub.publish(Wire.topicIn(destination),
                    Wire.in(sensor, record.getTemperature(), alarmLamport, alarmVector));
            events.accept("ALARME;p" + destination + ";sensor=" + sensor + ";origem=p" + partition
                    + ";seq=" + record.getSeq() + ";termo=" + record.getTerm());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalArgumentException e) {
            events.accept("ERRO;p" + destination + ";origem=alarme;detalhe=" + e.getMessage());
        }
    }

    @Override public void close() {
        closed = true;
        verification.shutdownNow();
        pubsub.close();
        for (Thread thread : subscriptions) thread.interrupt();
    }
}
