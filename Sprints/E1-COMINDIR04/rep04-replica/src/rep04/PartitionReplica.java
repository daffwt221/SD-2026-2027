package rep04;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class PartitionReplica {
    private static final int N = 3; // N fixado pela ficha; mantém a CLI da COMINDIR03.
    private PartitionReplica() { }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("ERRO: " + e.getMessage());
            System.err.println("Uso: PartitionReplica <id> <partições> [portoApi]");
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Réplica interrompida");
        }
    }

    private static void run(String[] args) throws IOException, InterruptedException {
        Wire.validateHeartbeatConfig();
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Número de argumentos inválido");
        }
        int id = Integer.parseInt(args[0]);
        if (id < 1) throw new IllegalArgumentException("O id da réplica deve ser positivo");
        int apiPort = args.length == 3 ? Integer.parseInt(args[2]) : 5001;
        if (apiPort < 1 || apiPort > 65535) {
            throw new IllegalArgumentException("Porto da API inválido");
        }
        String api = "http://127.0.0.1:" + apiPort + "/api/v0";
        Set<Integer> partitions = new LinkedHashSet<>();
        for (String token : args[1].split(",", -1)) {
            int partition = Integer.parseInt(token.trim());
            if (partition < 0 || partition >= N || !partitions.add(partition)) {
                throw new IllegalArgumentException("Partição inválida ou repetida: " + token);
            }
        }
        double loss = Double.parseDouble(System.getProperty("perda", "0"));
        String discardProperty = System.getProperty("descartar");
        long discard = discardProperty == null ? -1 : Long.parseLong(discardProperty);
        if (discardProperty != null && discard < 1) {
            throw new IllegalArgumentException("descartar deve ser um seq positivo");
        }
        List<PartitionState> states = new ArrayList<>();
        List<Thread> subscriptions = new ArrayList<>();
        MonitorLog events = new MonitorLog("monitor-replica" + id + ".txt");
        for (int partition : partitions) {
            RecordFile file = new RecordFile("replica-" + id + "-p" + partition + ".txt");
            PartitionState state = new PartitionState(id, partition, N, file, loss, discard, events);
            states.add(state);
            Thread subscription = new Thread(() -> {
                try {
                    new IpfsPubSub(api).subscribe(Wire.topicData(partition), state::receive);
                } catch (IOException | RuntimeException e) {
                    System.err.println("SUBSCRIÇÃO FALHOU: replica=" + id + " p=" + partition
                            + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.err.println("SUBSCRIÇÃO INTERROMPIDA: replica=" + id + " p=" + partition);
                } finally {
                    state.printSummary();
                }
            }, "replica-" + id + "-p" + partition);
            subscriptions.add(subscription);
        }
        // O monitor de cada estado torna a leitura no encerramento consistente.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (PartitionState state : states) state.printSummary();
        }, "resumo-replica-" + id));
        for (PartitionState state : states) state.printSummary();
        ScheduledExecutorService verification = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "verificacao-replica-" + id);
            thread.setDaemon(true);
            return thread;
        });
        try {
            verification.scheduleAtFixedRate(() -> {
                for (PartitionState state : states) state.checkHeartbeat();
            }, Wire.VERIFICACAO_MS, Wire.VERIFICACAO_MS, TimeUnit.MILLISECONDS);
            for (Thread subscription : subscriptions) subscription.start();
            for (Thread subscription : subscriptions) subscription.join();
        } finally {
            verification.shutdownNow();
        }
    }
}
