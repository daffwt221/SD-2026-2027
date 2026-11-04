package rep03;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Acumula conhecimento dos líderes e envia leituras derivadas pelo fluxo normal IN. */
public final class AlarmProcessor {
    private final Partitioner partitioner;
    private final IpfsPubSub pubsub;
    private final Object monitor = new Object();

    // O mesmo monitor protege os relógios e os identificadores já observados.
    private final List<Set<Long>> seen = new ArrayList<>();
    private long lamport;
    private final VectorClock vector;

    AlarmProcessor(int count, String api) {
        partitioner = new Partitioner(count);
        URI endpoint = URI.create(api);
        if (!("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()))
                || endpoint.getHost() == null) {
            throw new IllegalArgumentException("Endereço da API inválido: " + api);
        }
        pubsub = new IpfsPubSub(api);
        vector = new VectorClock(count);
        for (int k = 0; k < count; k++) seen.add(new HashSet<>());
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
        for (Thread subscription : subscriptions) subscription.start();
        for (Thread subscription : subscriptions) subscription.join();
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
        SensorRecord record;
        VectorClock receivedVector;
        try {
            String[] fields = Wire.regParts(message);
            int port = Integer.parseInt(fields[1]);
            if (fields[0].isBlank() || fields[0].codePoints()
                    .anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                    || port < 1 || port > 65535) {
                throw new IllegalArgumentException("Endereço ou porto inválido");
            }
            record = SensorRecord.fromLine(fields[2]);
            receivedVector = record.getVector();
            if (receivedVector.size() != partitioner.count()) {
                throw new IllegalArgumentException("Dimensão vetorial inválida");
            }
            if (partition < 0 || partition >= partitioner.count()
                    || partitioner.of(record.getSensorId()) != partition) {
                throw new IllegalArgumentException("Partição de origem inválida");
            }
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: p" + partition + " " + message + " (" + e.getMessage() + ")");
            return;
        }
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
