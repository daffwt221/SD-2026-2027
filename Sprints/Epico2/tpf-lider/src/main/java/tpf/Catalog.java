package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Catálogo append-only de confirmações históricas de armazenamento.
 * Uma confirmação persistida NÃO prova que a réplica ainda está disponível.
 */
public final class Catalog {
    public record ChunkInfo(String id, long lsnDe, long lsnAte, int keyVersion,
                            Set<String> replicasHistoricas) {}
    private static final class Mutable {
        long from, to; int version;
        final Set<String> replicas = new HashSet<>();
        Mutable(long from, long to, int version) {
            this.from = from; this.to = to; this.version = version;
        }
    }
    private final Path file;
    private final Map<String, Mutable> entries = new HashMap<>();

    public Catalog(Path file) throws IOException {
        this.file = file.toAbsolutePath();
        if (Files.exists(this.file)) load();
    }
    private void load() throws IOException {
        int lineNo = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            lineNo++;
            if (line.isBlank()) continue;
            String[] f = line.split(";", -1);
            if (f.length != 6 || !"ACK".equals(f[0]) || !validId(f[1]) || !validReplica(f[2]))
                throw new IOException("Catálogo inválido na linha " + lineNo);
            try {
                long from = Long.parseLong(f[3]), to = Long.parseLong(f[4]);
                int version = Integer.parseInt(f[5]);
                if (from < 1 || to < from || version < 1) throw new NumberFormatException();
                merge(f[1], f[2], from, to, version);
            } catch (NumberFormatException | IllegalStateException ex) {
                throw new IOException("Catálogo inválido na linha " + lineNo, ex);
            }
        }
    }
    private static boolean validId(String id) { return id.matches("[0-9a-f]{64}"); }
    private static boolean validReplica(String id) { return id.matches("[A-Za-z0-9_-]{1,40}"); }
    private void merge(String id, String replica, long from, long to, int version) {
        Mutable m = entries.get(id);
        if (m == null) { m = new Mutable(from, to, version); entries.put(id, m); }
        if (m.from != from || m.to != to || m.version != version)
            throw new IllegalStateException("Metadados divergentes para chunk " + id);
        m.replicas.add(replica);
    }
    /** Regista o ACK só depois de o líder o ter recebido para um STORE pendente. */
    public synchronized void recordAck(String id, String replica, Path header) throws IOException {
        if (!validId(id) || !validReplica(replica)) throw new IllegalArgumentException("ID inválido");
        Properties p = new Properties();
        try (var in = Files.newInputStream(header)) { p.load(in); }
        long from = Long.parseLong(p.getProperty("lsnDe", "-1"));
        long to = Long.parseLong(p.getProperty("lsnAte", "-1"));
        int version = Integer.parseInt(p.getProperty("keyVersion", "-1"));
        if (from < 1 || to < from || version < 1) throw new IOException("Header inválido");
        Mutable old = entries.get(id);
        if (old != null && (old.from != from || old.to != to || old.version != version))
            throw new IOException("Metadados em conflito");
        if (old != null && old.replicas.contains(replica)) return;
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        byte[] line = ("ACK;" + id + ";" + replica + ";" + from + ";" + to + ";" + version + "\n")
            .getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(line);
            while (buffer.hasRemaining()) ch.write(buffer);
            ch.force(true);
        }
        merge(id, replica, from, to, version);
    }
    public synchronized List<ChunkInfo> snapshot() {
        List<ChunkInfo> result = new ArrayList<>();
        for (var e : entries.entrySet()) {
            Mutable m = e.getValue();
            result.add(new ChunkInfo(e.getKey(), m.from, m.to, m.version, Set.copyOf(m.replicas)));
        }
        result.sort(java.util.Comparator.comparingLong(ChunkInfo::lsnDe));
        return List.copyOf(result);
    }
}
