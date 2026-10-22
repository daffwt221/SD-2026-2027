package rep02;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PartitionReplica {
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
            if (partition < 0 || !partitions.add(partition)) {
                throw new IllegalArgumentException("Partição negativa ou repetida: " + token);
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
        for (int partition : partitions) {
            RecordFile file = new RecordFile("replica-" + id + "-p" + partition + ".txt");
            PartitionState state = new PartitionState(id, partition, file, loss, discard);
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
        for (Thread subscription : subscriptions) subscription.start();
        for (Thread subscription : subscriptions) subscription.join();
    }
}
