package tpf;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/** Criptografia dos chunks. Apenas o líder deve possuir master.key. */
public final class ChunkCrypto {
    public static final int PAD_BYTES = Wire.PAD_BYTES;
    public static final int KEY_VERSION = 1;
    private static final byte[] INFO = "tpf-chunk-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AAD_PREFIX = "tpf-v1".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom RNG = new SecureRandom();

    private final byte[] masterKey;
    private final int keyVersion;

    public ChunkCrypto(byte[] masterKey) { this(masterKey, KEY_VERSION); }

    public ChunkCrypto(byte[] masterKey, int keyVersion) {
        if (masterKey == null || masterKey.length != 32)
            throw new IllegalArgumentException("master.key deve ter exatamente 32 bytes");
        if (keyVersion != KEY_VERSION)
            throw new IllegalArgumentException("Versão de chave ainda não suportada: " + keyVersion);
        this.masterKey = masterKey.clone();
        this.keyVersion = keyVersion;
    }

    public static ChunkCrypto fromFile(Path path) throws IOException {
        return new ChunkCrypto(Files.readAllBytes(path));
    }

    /** Gera a master key uma única vez. Nunca a regenera no reinício. */
    public static void generateMasterKeyIfAbsent(Path path) throws IOException {
        if (Files.exists(path)) {
            if (Files.size(path) != 32) throw new IOException("master.key existente com tamanho diferente de 32 bytes");
            return;
        }
        byte[] key = new byte[32];
        RNG.nextBytes(key);
        try {
            Files.write(path, key, java.nio.file.StandardOpenOption.CREATE_NEW);
            restringirPermissoes(path);
        } finally {
            java.util.Arrays.fill(key, (byte) 0);
        }
    }

    static void restringirPermissoes(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            java.nio.file.attribute.AclFileAttributeView acl = Files.getFileAttributeView(path,
                    java.nio.file.attribute.AclFileAttributeView.class);
            if (acl == null) throw new IOException("Sistema sem suporte para restringir permissões da chave", e);
            var owner = Files.getOwner(path);
            var builder = java.nio.file.attribute.AclEntry.newBuilder();
            builder.setType(java.nio.file.attribute.AclEntryType.ALLOW);
            builder.setPrincipal(owner);
            builder.setPermissions(java.nio.file.attribute.AclEntryPermission.values());
            acl.setAcl(java.util.List.of(builder.build()));
        }
    }

    public Sealed seal(byte[] plaintext) throws GeneralSecurityException {
        if (plaintext == null) throw new IllegalArgumentException("Texto nulo");
        byte[] salt = new byte[32];
        byte[] nonce = new byte[12];
        RNG.nextBytes(salt);
        RNG.nextBytes(nonce);
        byte[] dek = hkdf(masterKey, salt, INFO);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(keyVersion, salt, nonce));
            byte[] ciphertext = cipher.doFinal(addPadding(plaintext));
            return new Sealed(sha256Hex(ciphertext), keyVersion, salt, nonce, ciphertext);
        } finally {
            java.util.Arrays.fill(dek, (byte) 0);
        }
    }

    /** Verifica o hash e a autenticação GCM antes de devolver texto original. */
    public byte[] open(Sealed sealed) throws GeneralSecurityException {
        if (sealed == null) throw new IllegalArgumentException("Chunk nulo");
        byte[] ct = sealed.ciphertext();
        if (!MessageDigest.isEqual(sha256Hex(ct).getBytes(StandardCharsets.US_ASCII),
                sealed.chunkId().getBytes(StandardCharsets.US_ASCII)))
            throw new GeneralSecurityException("Hash do chunk inválido");
        if (sealed.keyVersion() != keyVersion)
            throw new GeneralSecurityException("Versão de chave desconhecida");
        byte[] dek = hkdf(masterKey, sealed.salt(), INFO);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(128, sealed.nonce()));
            cipher.updateAAD(aad(sealed.keyVersion(), sealed.salt(), sealed.nonce()));
            return removePadding(cipher.doFinal(ct));
        } finally {
            java.util.Arrays.fill(dek, (byte) 0);
        }
    }

    private static byte[] addPadding(byte[] original) {
        // Prefixo de 4 bytes guarda o comprimento original; padding aleatório evita ambiguidade.
        long n = (long) original.length + Integer.BYTES;
        long aligned = ((n + PAD_BYTES - 1) / PAD_BYTES) * PAD_BYTES;
        if (aligned > Integer.MAX_VALUE) throw new IllegalArgumentException("Chunk demasiado grande");
        ByteBuffer b = ByteBuffer.allocate((int) aligned).order(ByteOrder.BIG_ENDIAN);
        b.putInt(original.length);
        b.put(original);
        byte[] pad = new byte[b.remaining()];
        RNG.nextBytes(pad);
        b.put(pad);
        return b.array();
    }

    private static byte[] removePadding(byte[] padded) throws GeneralSecurityException {
        if (padded.length < PAD_BYTES || padded.length % PAD_BYTES != 0)
            throw new GeneralSecurityException("Padding inválido");
        ByteBuffer b = ByteBuffer.wrap(padded).order(ByteOrder.BIG_ENDIAN);
        int len = b.getInt();
        if (len < 0 || len > padded.length - Integer.BYTES)
            throw new GeneralSecurityException("Comprimento inválido");
        byte[] result = new byte[len];
        b.get(result);
        return result;
    }

    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        try {
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(info);
            mac.update((byte) 1);
            return mac.doFinal(); // HKDF-Expand: 32 bytes, um bloco SHA-256.
        } finally {
            java.util.Arrays.fill(prk, (byte) 0);
        }
    }

    static byte[] aad(int version, byte[] salt, byte[] nonce) {
        return ByteBuffer.allocate(AAD_PREFIX.length + Integer.BYTES + salt.length + nonce.length)
                .put(AAD_PREFIX).putInt(version).put(salt).put(nonce).array();
    }

    public static String sha256Hex(byte[] data) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
