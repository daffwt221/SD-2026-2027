package rep03;

import java.io.IOException;

/** Subscreve a entrada da partição e entrega ao líder apenas leituras válidas. */
final class PartitionInput implements Runnable {
    @FunctionalInterface
    interface ReadingHandler {
        void accept(String sensor, double temperature, long lamport, VectorClock vector);
    }

    private final int partition;
    private final Partitioner partitioner;
    private final IpfsPubSub pubsub;
    private final ReadingHandler onReading;

    PartitionInput(int partition, Partitioner partitioner, IpfsPubSub pubsub,
                   ReadingHandler onReading) {
        this.partition = partition;
        this.partitioner = partitioner;
        this.pubsub = pubsub;
        this.onReading = onReading;
    }

    @Override
    public void run() {
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
        long lamport;
        VectorClock vector;
        try {
            String[] fields = message.split(";", -1);
            if (fields.length != 5 || !fields[0].equals("IN") || fields[1].isBlank()
                    || fields[1].codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                    || !fields[2].equals(fields[2].trim())) {
                throw new IllegalArgumentException("IN mal formado");
            }
            sensor = fields[1];
            temperature = Double.parseDouble(fields[2]);
            if (!Double.isFinite(temperature)) {
                throw new IllegalArgumentException("Temperatura não finita");
            }
            lamport = Long.parseLong(fields[3]);
            if (lamport < 0) {
                throw new IllegalArgumentException("Lamport negativo");
            }
            String[] components = fields[4].split(",", -1);
            if (components.length != partitioner.count()) {
                throw new IllegalArgumentException("Dimensão vetorial inválida");
            }
            long[] values = new long[components.length];
            for (int i = 0; i < values.length; i++) {
                values[i] = Long.parseLong(components[i]);
                if (values[i] < 0) {
                    throw new IllegalArgumentException("Componente vetorial negativa");
                }
            }
            vector = new VectorClock(values);
        } catch (IllegalArgumentException e) {
            System.out.println("REJEITADO: " + message);
            return;
        }

        int expectedPartition = partitioner.of(sensor);
        if (expectedPartition != partition) {
            System.out.println("PARTIÇÃO ERRADA: " + sensor + " pertence a p" + expectedPartition);
            return;
        }

        onReading.accept(sensor, temperature, lamport, vector);
    }
}
