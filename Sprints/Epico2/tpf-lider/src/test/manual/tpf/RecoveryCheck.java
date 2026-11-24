package tpf;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** Teste manual de restauração e recusa de ficheiros adulterados. */
public final class RecoveryCheck {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tpf-recovery-check-");
        Path keyFile = root.resolve("master.key");
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        Files.write(keyFile, key);
        byte[] original = "Teste de recuperação distribuída".getBytes(StandardCharsets.UTF_8);
        Sealed sealed = new ChunkCrypto(key).seal(original);
        Properties props = new Properties();
        props.setProperty("lsnDe", "1"); props.setProperty("lsnAte", "1");
        props.setProperty("keyVersion", Integer.toString(sealed.keyVersion()));
        props.setProperty("tamanhoPlaintext", Integer.toString(original.length));
        props.setProperty("salt", HexFormat.of().formatHex(sealed.salt()));
        props.setProperty("nonce", HexFormat.of().formatHex(sealed.nonce()));
        var out = new java.io.ByteArrayOutputStream(); props.store(out, "TPF metadata");
        byte[] header = out.toByteArray();
        Catalog.ChunkInfo info = new Catalog.ChunkInfo(sealed.chunkId(), 1, 1, 1, Set.of("A"));
        Path restore = root.resolve("selados");
        Recovery.validateAndRestore(restore, keyFile, info, header, sealed.ciphertext());
        if (!Arrays.equals(Files.readAllBytes(restore.resolve(sealed.chunkId() + ".bin")), sealed.ciphertext()))
            throw new AssertionError("Restauro incorreto");
        if (!Arrays.equals(Files.readAllBytes(restore.resolve(sealed.chunkId() + ".hdr")), header))
            throw new AssertionError("Header incorreto");
        byte[] bad = sealed.ciphertext(); bad[0] ^= 1;
        boolean rejected = false;
        try { Recovery.validateAndRestore(root.resolve("bad"), keyFile, info, header, bad); }
        catch (java.security.GeneralSecurityException expected) { rejected = true; }
        if (!rejected || Files.exists(root.resolve("bad").resolve(sealed.chunkId() + ".bin")))
            throw new AssertionError("Alteração de ciphertext aceite");
        byte[] otherKey = new byte[32]; new SecureRandom().nextBytes(otherKey);
        Path wrongKey = root.resolve("wrong.key"); Files.write(wrongKey, otherKey);
        rejected = false;
        try { Recovery.validateAndRestore(root.resolve("wrong"), wrongKey, info, header, sealed.ciphertext()); }
        catch (java.security.GeneralSecurityException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("GCM com chave errada não foi rejeitado");
        System.out.println("RECOVERY CHECK OK: restauro, SHA-256, GCM e rejeição de adulteração");
    }
}
