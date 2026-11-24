package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Base64;

/** Armazenamento zero-knowledge: só ciphertext e metadados, nunca master.key. */
public final class ReplicaStorage {
    private final Path root;
    private final long quota;

    public ReplicaStorage(Path root, long quota) throws IOException {
        if (quota < 0) throw new IllegalArgumentException("quota negativa");
        this.root = root.toAbsolutePath().normalize();
        this.quota = quota;
        Files.createDirectories(root);
    }

    public synchronized String store(String id, String header64, String bin64) throws Exception {
        if (!id.matches("[0-9a-f]{64}")) return "NACK;" + id + ";ID_INVALIDO";
        byte[] header = Base64.getDecoder().decode(header64);
        byte[] ciphertext = Base64.getDecoder().decode(bin64);
        if (header.length > 4096 || ciphertext.length < 17 || ciphertext.length > 70000)
            return "NACK;" + id + ";TAMANHO_INVALIDO";
        String computed = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ciphertext));
        if (!computed.equals(id)) return "NACK;" + id + ";HASH_INVALIDO";
        Properties props = new Properties();
        try (var in = new java.io.ByteArrayInputStream(header)) { props.load(in); }
        long from = Long.parseLong(props.getProperty("lsnDe", "-1"));
        long to = Long.parseLong(props.getProperty("lsnAte", "-1"));
        int version = Integer.parseInt(props.getProperty("keyVersion", "-1"));
        int plain = Integer.parseInt(props.getProperty("tamanhoPlaintext", "-1"));
        byte[] salt = HexFormat.of().parseHex(props.getProperty("salt", ""));
        byte[] nonce = HexFormat.of().parseHex(props.getProperty("nonce", ""));
        if (from < 1 || to < from || version < 1 || plain < 0 || plain > 65536 || salt.length != 32 || nonce.length != 12)
            return "NACK;" + id + ";HEADER_INVALIDO";
        Path bin = root.resolve(id + ".bin");
        Path hdr = root.resolve(id + ".hdr");
        if (Files.exists(bin) && Files.exists(hdr)) {
            byte[] old = Files.readAllBytes(bin);
            if (MessageDigest.isEqual(old, ciphertext) && MessageDigest.isEqual(Files.readAllBytes(hdr), header))
                return "ACK;" + id + ";JA_EXISTIA";
            return "NACK;" + id + ";CONFLITO";
        }
        if (Files.exists(bin) || Files.exists(hdr)) return "NACK;" + id + ";PAR_INCOMPLETO";
        if (bytesUsed() + ciphertext.length + header.length > quota)
            return "NACK;" + id + ";QUOTA_EXCEDIDA";
        Path tempBin = Files.createTempFile(root, ".part-", ".tmp");
        Path tempHdr = Files.createTempFile(root, ".part-", ".tmp");
        try {
            durableWrite(tempBin, ciphertext);
            durableWrite(tempHdr, header);
            Files.move(tempBin, bin, StandardCopyOption.ATOMIC_MOVE);
            Files.move(tempHdr, hdr, StandardCopyOption.ATOMIC_MOVE);
            return "ACK;" + id + ";GUARDADO";
        } finally {
            Files.deleteIfExists(tempBin);
            Files.deleteIfExists(tempHdr);
        }
    }

    /** Devolve apenas o par cifrado. Nunca a chave privada nem o plaintext. */
    public synchronized String get(String id) throws Exception {
        if (!id.matches("[0-9a-f]{64}")) return null;
        Path bin = root.resolve(id + ".bin");
        Path hdr = root.resolve(id + ".hdr");
        if (!Files.isRegularFile(bin) || !Files.isRegularFile(hdr)) return null;
        byte[] cipher = Files.readAllBytes(bin);
        byte[] header = Files.readAllBytes(hdr);
        if (cipher.length > 70000 || header.length > 4096) return null;
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cipher));
        if (!hash.equals(id)) return null;
        return "DATA;" + id + ";" + Base64.getEncoder().encodeToString(header)
            + ";" + Base64.getEncoder().encodeToString(cipher);
    }

    private static void durableWrite(Path file, byte[] bytes) throws IOException {
        try (FileChannel fc = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            while (buf.hasRemaining()) fc.write(buf);
            fc.force(true);
        }
    }

    public synchronized long bytesUsed() throws IOException {
        long sum = 0;
        try (var stream = Files.list(root)) {
            for (Path p : stream.filter(p -> p.toString().endsWith(".bin") || p.toString().endsWith(".hdr")).toList())
                sum += Files.size(p);
        }
        return sum;
    }
}
