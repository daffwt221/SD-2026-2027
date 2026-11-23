package tpf;

import java.util.Arrays;

/** Chunk cifrado e imutável, pronto para ser enviado às réplicas. */
public final class Sealed {
    private final String chunkId;
    private final int keyVersion;
    private final byte[] salt;
    private final byte[] nonce;
    private final byte[] ciphertext;

    public Sealed(String chunkId, int keyVersion, byte[] salt, byte[] nonce, byte[] ciphertext) {
        if (chunkId == null || !chunkId.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("chunkId inválido");
        if (keyVersion < 1 || salt == null || salt.length != 32 || nonce == null || nonce.length != 12
                || ciphertext == null || ciphertext.length < 16)
            throw new IllegalArgumentException("Cabeçalho ou conteúdo inválido");
        this.chunkId = chunkId;
        this.keyVersion = keyVersion;
        this.salt = salt.clone();
        this.nonce = nonce.clone();
        this.ciphertext = ciphertext.clone();
    }

    public String chunkId() { return chunkId; }
    public int keyVersion() { return keyVersion; }
    public byte[] salt() { return salt.clone(); }
    public byte[] nonce() { return nonce.clone(); }
    public byte[] ciphertext() { return ciphertext.clone(); }
}
