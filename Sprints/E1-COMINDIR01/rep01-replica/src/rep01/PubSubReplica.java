package rep01;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PubSubReplica {
    static final String TOPIC = System.getProperty("rep01.topic", "rep01-temperaturas");

    public static void main(String[] args) {
        String id = args.length > 0 ? args[0] : "1";
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "resumo-rajadas");
            thread.setDaemon(true);
            return thread;
        });
        try {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 5001;
            int size = args.length > 2 ? Integer.parseInt(args[2]) : 1000;
            Long start = args.length > 3 ? Long.valueOf(args[3]) : null;
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Porto inválido.");
            ReplicaState state = new ReplicaState(id, size, start);
            IpfsPubSub pubsub = new IpfsPubSub("http://127.0.0.1:" + port + "/api/v0");
            reporter.scheduleAtFixedRate(state::reportIfIdle, 1, 1, TimeUnit.SECONDS);
            System.out.println("Réplica " + id + " a subscrever " + TOPIC + " no porto " + port);
            pubsub.subscribe(TOPIC, line -> {
                long receivedAt = System.currentTimeMillis();
                try {
                    state.accept(line, receivedAt);
                } catch (IOException e) {
                    // Uma falha de disco não é uma mensagem mal formada.
                    throw new UncheckedIOException(e);
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Interrompido: " + e.getClass().getSimpleName());
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            System.out.println("ERRO: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            reporter.shutdownNow();
        }
    }
}
