package tpf;

import java.io.IOException;
import java.nio.file.*;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Distribuição de chunks selados para réplica A, com ACK e reenvio simples. */
public final class ChunkDispatcher {
    private final PubSubHub hub;
    private final Path dir;
    private final Map<String, Long> awaiting = new ConcurrentHashMap<>();
    private final java.util.Set<String> acknowledged = ConcurrentHashMap.newKeySet();

    public ChunkDispatcher(PubSubHub hub, Path directory) {
        this.hub = hub;
        this.dir = directory;
    }

    public void onReply(String replica, String reply) {
        String[] f = reply.split(";", 3);
        if (f.length < 2 || !f[1].matches("[0-9a-f]{64}")) return;
        String key = replica + ":" + f[1];
        if ("ACK".equals(f[0])) {
            acknowledged.add(key);
            awaiting.remove(key);
            System.out.println("Confirmado por " + replica + ": " + f[1] + " (" + (f.length > 2 ? f[2] : "") + ")");
        } else if ("NACK".equals(f[0])) {
            awaiting.remove(key);
            System.err.println("Recusado por " + replica + ": " + reply);
        }
    }

    public void dispatch(String replica) throws IOException {
        if (!hub.isSubscribed(replica)) return;
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.newDirectoryStream(dir, "*.hdr")) {
            for (Path hdr : stream) {
                String name = hdr.getFileName().toString();
                String id = name.substring(0, name.length() - 4);
                if (!id.matches("[0-9a-f]{64}")) continue;
                Path bin = dir.resolve(id + ".bin");
                if (!Files.exists(bin)) continue;
                String key = replica + ":" + id;
                if (acknowledged.contains(key)) continue;
                long now = System.currentTimeMillis();
                if (now - awaiting.getOrDefault(key, 0L) < 10_000L) continue;
                byte[] header = Files.readAllBytes(hdr);
                byte[] cipher = Files.readAllBytes(bin);
                String payload = "STORE;" + id + ";" + Base64.getEncoder().encodeToString(header)
                        + ";" + Base64.getEncoder().encodeToString(cipher);
                awaiting.put(key, now);
                hub.publish("tpf-r-" + replica, payload);
                System.out.println("STORE para " + replica + ": " + id);
            }
        }
    }
}
