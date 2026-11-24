package tpf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Recupera um par .hdr/.bin de uma réplica autenticada, sem transferir master.key. */
public final class Recovery {
    private static final long TIMEOUT_MS = 10_000;
    private static final int MAX_DATA_CHARS = 128 * 1024;
    private final PubSubHub hub;
    private final Catalog catalog;
    private final Path dir;
    private final Path masterKey;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private record Pending(String replica, long createdAt) {}

    public Recovery(PubSubHub hub, Catalog catalog, Path dir, Path masterKey) {
        this.hub = Objects.requireNonNull(hub);
        this.catalog = Objects.requireNonNull(catalog);
        this.dir = Objects.requireNonNull(dir);
        this.masterKey = Objects.requireNonNull(masterKey);
    }

    /** Pede a recuperação de chunks em falta, até ao máximo indicado, em cada ronda. */
    public int requestMissing(int maxRequests) throws IOException {
        if (maxRequests < 0) throw new IllegalArgumentException("maxRequests negativo");
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> now - e.getValue().createdAt() >= TIMEOUT_MS);
        if (maxRequests == 0) return 0;
        if (!Files.exists(masterKey)) throw new IOException("master.key não encontrada; recuperação recusada");
        Set<String> active = new HashSet<>();
        for (var replica : hub.eligibleReplicas()) active.add(replica.id());
        int count = 0;
        for (Catalog.ChunkInfo chunk : catalog.snapshot()) {
            if (count >= maxRequests) break;
            String id = chunk.id();
            if (Files.isRegularFile(dir.resolve(id + ".bin")) &&
                Files.isRegularFile(dir.resolve(id + ".hdr"))) continue;
            if (pending.containsKey(id)) continue;
            // Só pede a quem já confirmou o armazenamento e está autenticado/ativo.
            for (String replica : new TreeSet<>(chunk.replicasHistoricas())) {
                if (!active.contains(replica)) continue;
                pending.put(id, new Pending(replica, now));
                hub.publish("tpf-r-" + replica, "GET;" + id);
                System.out.println("RECOVERY GET para " + replica + ": " + id);
                count++;
                break;
            }
        }
        return count;
    }

    /** Ignora DATA inesperado ou proveniente de outra réplica. */
    public void onReply(String replica, String reply) {
        if (reply.length() > MAX_DATA_CHARS || !reply.startsWith("DATA;")) return;
        String[] f = reply.split(";", 4);
        if (f.length != 4 || !f[1].matches("[0-9a-f]{64}")) return;
        Pending task = pending.get(f[1]);
        if (task == null || !task.replica().equals(replica) ||
            System.currentTimeMillis() - task.createdAt() > TIMEOUT_MS) return;
        try {
            Catalog.ChunkInfo info = catalog.snapshot().stream()
                .filter(c -> c.id().equals(f[1])).findFirst()
                .orElseThrow(() -> new IOException("Chunk ausente do catálogo"));
            byte[] header = Base64.getDecoder().decode(f[2]);
            byte[] cipher = Base64.getDecoder().decode(f[3]);
            validateAndRestore(dir, masterKey, info, header, cipher);
            System.out.println("RECOVERY OK: " + f[1] + " (origem " + replica + ")");
        } catch (Exception ex) {
            System.err.println("RECOVERY recusado para " + f[1] + ": " + ex.getMessage());
        } finally {
            pending.remove(f[1], task);
        }
    }

    /** Testável sem rede: verifica hash, metadados, GCM e só depois grava. */
    static void validateAndRestore(Path dir, Path masterKey, Catalog.ChunkInfo info,
                                   byte[] header, byte[] ciphertext) throws Exception {
        if (header.length == 0 || header.length > 4096 || ciphertext.length < 17 || ciphertext.length > 70000)
            throw new IOException("Tamanho inválido");
        String id = ChunkCrypto.sha256Hex(ciphertext);
        if (!id.equals(info.id())) throw new GeneralSecurityException("SHA-256 inválido");
        Properties props = new Properties();
        try (var in = new ByteArrayInputStream(header)) { props.load(in); }
        long from = Long.parseLong(props.getProperty("lsnDe", "-1"));
        long to = Long.parseLong(props.getProperty("lsnAte", "-1"));
        int version = Integer.parseInt(props.getProperty("keyVersion", "-1"));
        int plainSize = Integer.parseInt(props.getProperty("tamanhoPlaintext", "-1"));
        byte[] salt = HexFormat.of().parseHex(props.getProperty("salt", ""));
        byte[] nonce = HexFormat.of().parseHex(props.getProperty("nonce", ""));
        if (from != info.lsnDe() || to != info.lsnAte() || version != info.keyVersion() ||
            salt.length != 32 || nonce.length != 12 || plainSize < 0 || plainSize > 65536)
            throw new IOException("Header incompatível com o catálogo");
        byte[] plaintext = ChunkCrypto.fromFile(masterKey).open(new Sealed(id, version, salt, nonce, ciphertext));
        if (plaintext.length != plainSize) throw new GeneralSecurityException("Tamanho após decifragem divergente");
        Files.createDirectories(dir);
        Path bin = dir.resolve(id + ".bin");
        Path hdr = dir.resolve(id + ".hdr");
        // Evitar sobrescrever conteúdo existente ou criar pares inconsistentes.
        if (Files.exists(bin) && !MessageDigest.isEqual(Files.readAllBytes(bin), ciphertext))
            throw new IOException("Conflito com ficheiro .bin local");
        if (Files.exists(hdr) && !MessageDigest.isEqual(Files.readAllBytes(hdr), header))
            throw new IOException("Conflito com ficheiro .hdr local");
        Path tempBin = Files.createTempFile(dir, ".recovery-", ".tmp");
        Path tempHdr = Files.createTempFile(dir, ".recovery-", ".tmp");
        try {
            durableWrite(tempBin, ciphertext);
            durableWrite(tempHdr, header);
            if (!Files.exists(bin)) Files.move(tempBin, bin, StandardCopyOption.ATOMIC_MOVE);
            if (!Files.exists(hdr)) Files.move(tempHdr, hdr, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tempBin);
            Files.deleteIfExists(tempHdr);
        }
    }

    private static void durableWrite(Path file, byte[] data) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ByteBuffer b = ByteBuffer.wrap(data);
            while (b.hasRemaining()) ch.write(b);
            ch.force(true);
        }
    }
}
