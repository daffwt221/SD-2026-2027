package tpf;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Distribuidor transitório: R=3 cópias desejadas; W=2 ACK para durabilidade. */
public final class ChunkDispatcher {
    private static final int R = 3, W = 2;
    private static final long TIMEOUT_MS = 10_000, MARGIN_BYTES = 4096;
    private final PubSubHub hub;
    private final Path dir;
    private final Map<String, Long> awaiting = new HashMap<>(); // replica:id -> instante STORE
    private final Map<String, Long> reserved = new HashMap<>(); // replica:id -> bytes reservados
    private final Map<String, Set<String>> acked = new HashMap<>(); // chunk -> replicas com ACK
    private final Map<String, String> printed = new HashMap<>();

    public ChunkDispatcher(PubSubHub hub, Path directory) {
        this.hub = hub; this.dir = directory;
    }

    public synchronized void onReply(String replica, String reply) {
        String[] f = reply.split(";", 3);
        if (f.length != 3 || !f[1].matches("[0-9a-f]{64}")) return;
        String key = replica + ":" + f[1];
        // Nunca aceitar ACK espontâneo ou de um STORE antigo.
        if (!awaiting.containsKey(key)) return;
        awaiting.remove(key); reserved.remove(key);
        if ("ACK".equals(f[0]) && ("GUARDADO".equals(f[2]) || "JA_EXISTIA".equals(f[2]))) {
            acked.computeIfAbsent(f[1], unused -> new HashSet<>()).add(replica);
            int n = acked.get(f[1]).size();
            System.out.println("ACK " + replica + " chunk " + f[1] + " (" + n + "/" + R + ")");
            if (n == W) System.out.println("DURAVEL (W=" + W + "): " + f[1]);
        } else if ("NACK".equals(f[0])) {
            System.err.println("NACK de " + replica + ": " + reply);
        }
    }

    public synchronized void dispatch() throws IOException {
        if (!Files.isDirectory(dir)) return;
        long now = System.currentTimeMillis();
        // Remove reservas de tentativas sem ACK para permitir o reenvio.
        awaiting.entrySet().removeIf(e -> {
            if (now - e.getValue() <= TIMEOUT_MS) return false;
            reserved.remove(e.getKey()); return true;
        });
        List<PubSubHub.ReplicaStatus> active = hub.eligibleReplicas();
        Map<String, Long> reservedByReplica = new HashMap<>();
        for (var e : reserved.entrySet()) {
            String replica = e.getKey().substring(0, e.getKey().indexOf(':'));
            reservedByReplica.merge(replica, e.getValue(), Long::sum);
        }
        try (var stream = Files.newDirectoryStream(dir, "*.hdr")) {
            for (Path hdr : stream) {
                String fileName = hdr.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - 4);
                if (!id.matches("[0-9a-f]{64}")) continue;
                Path bin = dir.resolve(id + ".bin");
                if (!Files.exists(bin)) continue;
                byte[] header = Files.readAllBytes(hdr);
                byte[] cipher = Files.readAllBytes(bin);
                long bytes = header.length + cipher.length;
                Set<String> already = acked.getOrDefault(id, Set.of());
                Set<String> pending = new HashSet<>();
                for (String key : awaiting.keySet()) if (key.endsWith(":" + id))
                    pending.add(key.substring(0, key.indexOf(':')));
                String state = already.size() >= R ? "REPLICADO" : already.size() >= W ? "DURAVEL" : "SUBREPLICADO";
                String stateKey = state + ":" + already.size();
                if (!stateKey.equals(printed.put(id, stateKey)))
                    System.out.println(state + " chunk " + id + " (ACK " + already.size() + "/" + R + ")");
                if (already.size() + pending.size() >= R) continue;
                Set<String> exclude = new HashSet<>(already); exclude.addAll(pending);
                List<Placement.Candidate> candidates = new ArrayList<>();
                for (var status : active) {
                    long free = Math.max(0, status.quota() - status.used() -
                        reservedByReplica.getOrDefault(status.id(), 0L));
                    candidates.add(new Placement.Candidate(status.id(), free));
                }
                List<String> selected = Placement.choose(id, bytes, MARGIN_BYTES,
                    R - already.size() - pending.size(), candidates, exclude);
                if (selected.isEmpty()) continue;
                String payload = "STORE;" + id + ";" + Base64.getEncoder().encodeToString(header)
                    + ";" + Base64.getEncoder().encodeToString(cipher);
                for (String replica : selected) {
                    String key = replica + ":" + id;
                    awaiting.put(key, now);
                    reserved.put(key, bytes);
                    reservedByReplica.merge(replica, bytes, Long::sum);
                    hub.publish("tpf-r-" + replica, payload);
                    System.out.println("STORE para " + replica + ": " + id);
                }
            }
        }
    }
}
