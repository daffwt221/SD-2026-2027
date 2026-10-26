package rep03;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

public final class Producer {
    private final Partitioner partitioner;
    private final IpfsPubSub pubsub;
    private final Random random = new Random();

    Producer(int n, String api) {
        partitioner = new Partitioner(n);
        pubsub = new IpfsPubSub(api);
    }

    private void publish(String sensor, double temperature) throws IOException, InterruptedException {
        if (sensor.isBlank() || sensor.indexOf(';') >= 0
                || sensor.chars().anyMatch(Character::isWhitespace) || !Double.isFinite(temperature)) {
            throw new IllegalArgumentException("Sensor/temperatura inválidos");
        }
        int k = partitioner.of(sensor);
        pubsub.publish(Wire.topicIn(k),
                Wire.in(sensor, temperature, 0L, new VectorClock(partitioner.count())));
        System.out.println("PUBLICADO: " + sensor + " " + temperature + " → p" + k);
    }

    private void burst(int n, int sensors, boolean hot) throws IOException, InterruptedException {
        if (n < 1 || sensors < 1 || (hot && sensors < 2)) {
            throw new IllegalArgumentException("n >= 1; s >= 1 (s >= 2 para rajada-quente)");
        }
        int[] counts = new int[partitioner.count()];
        // Exatamente floor(0,8*n) leituras quentes, em posições aleatórias.
        int remainingHot = hot ? (int) (4L * n / 5L) : 0;
        long first = 0;
        long last = 0;
        int sent = 0;
        try {
            for (int i = 0; i < n; i++) {
                int sensorNumber;
                if (!hot) {
                    sensorNumber = 1 + random.nextInt(sensors);
                } else if (random.nextInt(n - i) < remainingHot) {
                    sensorNumber = 1;
                    remainingHot--;
                } else {
                    sensorNumber = 2 + random.nextInt(sensors - 1);
                }
                String sensor = String.format(Locale.ROOT, "S%02d", sensorNumber);
                double temperature = 15.0 + 15.0 * random.nextDouble();
                if (sent == 0) first = System.currentTimeMillis();
                publish(sensor, temperature);
                last = System.currentTimeMillis();
                counts[partitioner.of(sensor)]++;
                sent++;
            }
        } finally {
            System.out.println("RAJADA: tipo=" + (hot ? "quente" : "uniforme") + " pedidas=" + n
                    + " publicadas=" + sent + " primeiraPublicacaoMs=" + first
                    + " ultimaPublicacaoMs=" + last
                    + " envioMs=" + (sent == 0 ? 0 : last - first)
                    + " porParticao=" + Arrays.toString(counts)
                    + " completa=" + (sent == n));
        }
    }

    void command(String line) throws IOException, InterruptedException {
        String[] parts = line.trim().split("\\s+");
        if (parts[0].equals("rajada") || parts[0].equals("rajada-quente")) {
            if (parts.length != 3) throw new IllegalArgumentException("Usar rajada[-quente] <n> <s>");
            burst(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), parts[0].equals("rajada-quente"));
        } else {
            if (parts.length != 2) throw new IllegalArgumentException("Usar <sensor> <temperatura>");
            publish(parts[0], Double.parseDouble(parts[1]));
        }
    }

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Uso: Producer <N> [api]");
            System.exit(1);
        }
        try {
            Producer producer = new Producer(Integer.parseInt(args[0]),
                    args.length == 2 ? args[1] : "http://127.0.0.1:5001/api/v0");
            System.out.println("Comandos: <sensor> <temperatura>, rajada <n> <s>, rajada-quente <n> <s>.");
            try (BufferedReader input = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        producer.command(line);
                    } catch (IllegalArgumentException | IOException e) {
                        System.out.println("REJEITADO: " + line + " (" + e.getMessage() + ")");
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Produtor interrompido.");
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Produtor terminou: " + e.getMessage());
            System.exit(1);
        }
    }
}
