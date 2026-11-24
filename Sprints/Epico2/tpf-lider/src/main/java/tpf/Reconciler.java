package tpf;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Reconciliação a cada cinco segundos, com máximo de 20 novas tarefas por ronda.
 *
 * A origem das cópias nesta fase é a pasta selados/ do líder. Réplicas que
 * regressam vazias recebem STORE; réplicas com cópias intactas devolvem
 * JA_EXISTIA. Não recupera ainda um chunk cujo original tenha sido perdido
 * pelo líder, nem cria uma quarta identidade se A/B/C estiver indisponível.
 */
public final class Reconciler implements AutoCloseable {
    public static final int MAX_TASKS_PER_ROUND = 20;
    public static final int INTERVAL_SECONDS = 5;

    private final ChunkDispatcher dispatcher;
    private final ReplicaRegistry registry;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tpf-reconciler");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ReplicaRegistry.State> lastState = new HashMap<>();

    public Reconciler(ChunkDispatcher dispatcher, ReplicaRegistry registry) {
        if (dispatcher == null || registry == null) throw new IllegalArgumentException("Dependências em falta");
        this.dispatcher = dispatcher;
        this.registry = registry;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::runOnce, 2, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Pode ser chamado em testes ou manualmente. */
    public void runOnce() {
        try {
            // Atualiza estados antes de escolher destinos para a reconciliação.
            for (var replica : registry.snapshot(System.currentTimeMillis())) {
                var before = lastState.put(replica.id(), replica.state());
                if (before != replica.state())
                    System.out.println("REPLICA " + replica.id() + ": " + replica.state());
            }
            int sent = dispatcher.dispatch(MAX_TASKS_PER_ROUND);
            if (sent > 0) System.out.println("RECONCILER: " + sent + " STORE nesta ronda");
        } catch (IOException | RuntimeException ex) {
            System.err.println("RECONCILER erro: " + ex.getMessage());
        }
    }

    @Override public void close() {
        executor.shutdownNow();
    }
}
