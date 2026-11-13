package rep04;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Acumula conhecimento dos líderes e envia leituras derivadas pelo fluxo normal IN. */
public final class AlarmProcessor {
    private final Partitioner partitioner;
    private final IpfsPubSub pubsub;
    private final Object monitor = new Object();
    private final List<HeartbeatMonitor> heartbeats = new ArrayList<>();
    private final MonitorLog events = new MonitorLog("monitor-processador.txt");

    // O mesmo monitor protege os relógios e os identificadores já observados.
    private final List<Set<Long>> seen = new ArrayList<>();
    private long lamport;
    private final VectorClock vector;

    AlarmProcessor(int count, String api) {
        Wire.validateHeartbeatConfig();
        partitioner = new Partitioner(count);
        URI endpoint = URI.create(api);
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null) {
            throw new IllegalArgumentException("Endereço da API inválido: " + api);
        }
        pubsub = new IpfsPubSub(api);
        vector = new VectorClock(count);
        for (int k = 0; k < count; k++) {
            seen.add(new HashSet<>());
            heartbeats.add(new HeartbeatMonitor(k, Wire.PERIODO_HB_MS, Wire.FATOR_TIMEOUT, events));
        }
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            usage();
            System.exit(1);
        }
        try {
            new AlarmProcessor(Integer.parseInt(args[0]),
                    args.length == 2 ? args[1] : "http://127.0.0.1:5001/api/v0").run();
        } catch (IllegalArgumentException e) {
            System.err.println("Configuração inválida: " + e.getMessage());
            usage();
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Processador interrompido.");
        }
    }

    private static void usage() {
        System.err.println("Uso: AlarmProcessor <N> [api]");
    }

    private void run() throws InterruptedException {
        List<Thread> subscriptions = new ArrayList<>();
        for (int k = 0; k < partitioner.count(); k++) {
            final int partition = k;
            subscriptions.add(new Thread(() -> subscribe(partition), "processador-p" + partition));
        }
        ScheduledExecutorService verification = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "verificacao-processador");
            thread.setDaemon(true);
            return thread;
        });
        try {
            verification.scheduleAtFixedRate(() -> {
                for (HeartbeatMonitor heartbeat : heartbeats) heartbeat.check();
            }, Wire.VERIFICACAO_MS, Wire.VERIFICACAO_MS, TimeUnit.MILLISECONDS);
            for (Thread subscription : subscriptions) subscription.start();
            for (Thread subscription : subscriptions) subscription.join();
        } finally {
            verification.shutdownNow();
        }
    }

    private void subscribe(int partition) {
        try {
            pubsub.subscribe(Wire.topicData(partition), message -> receive(partition, message));
            System.out.println("Subscrição terminada: " + Wire.topicData(partition));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Subscrição interrompida: " + Wire.topicData(partition));
        } catch (IOException | RuntimeException e) {
            System.err.println("Subscrição falhou: " + Wire.topicData(partition)
                    + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    void receive(int partition, String message) {
        try {
            if (partition < 0 || partition >= partitioner.count()) {
                throw new IllegalArgumentException("Partição de origem inválida");
            }
            Batch batch = Batch.decode(message, partitioner.count());
            for (SensorRecord record : batch.getRecords()) {
                if (partitioner.of(record.getSensorId()) != partition
                        || record.getVector().get(partition) != record.getSeq()) {
                    throw new IllegalArgumentException("Registo incompatível com p" + partition);
                }
            }
            heartbeats.get(partition).onHeartbeat(batch.getInc(), batch.getHb());
            events.accept(batch.receivedEvent(partition));
            for (SensorRecord record : batch.getRecords()) processRecord(partition, record);
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: p" + partition + " " + message + " (" + e.getMessage() + ")");
        }
    }

    private void processRecord(int partition, SensorRecord record) {
        VectorClock receivedVector = record.getVector();
        if (record.getSensorId().startsWith("ALARM-")) {
            System.out.println("IGNORADO: " + record.getSensorId() + " p" + partition + "#" + record.getSeq());
            return;
        }

        String alarmSensor;
        int destination;
        long alarmLamport;
        VectorClock alarmVector;
        synchronized (monitor) {
            if (!seen.get(partition).add(record.getSeq())) {
                System.out.println("DUPLICADO: p" + partition + "#" + record.getSeq());
                return;
            }
            // Só os líderes criam eventos: observar ou enviar um alarme não incrementa relógios.
            lamport = Math.max(lamport, record.getLamport());
            vector.merge(receivedVector);
            System.out.println("OBSERVADO: p" + partition + "#" + record.getSeq()
                    + " L=" + lamport + " V=" + vector);
            if (record.getTemperature() < Wire.LIMIAR_ALARME) return;

            alarmSensor = "ALARM-" + record.getSensorId();
            destination = partitioner.of(alarmSensor);
            alarmLamport = lamport;
            alarmVector = vector.copy();
        }

        // A rede fica fora do monitor; os dois carimbos pertencem ao mesmo snapshot.
        if (heartbeats.get(destination).state() == HeartbeatMonitor.State.SUSPEITO) {
            System.out.println("AVISO: líder p" + destination + " suspeito; alarme "
                    + alarmSensor + " pode perder-se");
        }
        try {
            pubsub.publish(Wire.topicIn(destination),
                    Wire.in(alarmSensor, record.getTemperature(), alarmLamport, alarmVector));
            System.out.println("ALARME: " + record.getSensorId() + " p" + partition
                    + "#" + record.getSeq() + " → p" + destination);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("PUBLICAÇÃO INTERROMPIDA: " + alarmSensor);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("PUBLICAÇÃO FALHOU: " + alarmSensor + " " + e.getMessage());
        }
    }
}
