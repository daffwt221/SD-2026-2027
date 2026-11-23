package tpf;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/** Testes sem JUnit e sem Discord. Executar a partir das classes compiladas. */
public final class CryptoCheck {
    public static void main(String[] args) throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        ChunkCrypto crypto = new ChunkCrypto(key);
        for (int size : new int[]{0, 1, 4091, 4092, 4096, 65536}) {
            byte[] original = new byte[size];
            new SecureRandom().nextBytes(original);
            Sealed s = crypto.seal(original);
            if (!Arrays.equals(original, crypto.open(s))) throw new AssertionError("Round-trip falhou: " + size);
            if ((s.ciphertext().length - 16) % ChunkCrypto.PAD_BYTES != 0)
                throw new AssertionError("Padding incorreto");
        }
        byte[] text = "Olá mundo".getBytes(StandardCharsets.UTF_8);
        Sealed one = crypto.seal(text);
        Sealed two = crypto.seal(text);
        if (one.chunkId().equals(two.chunkId())) throw new AssertionError("Salts/nonces não aleatórios");
        byte[] corrupted = one.ciphertext();
        corrupted[0] ^= 1;
        Sealed altered = new Sealed(one.chunkId(), one.keyVersion(), one.salt(), one.nonce(), corrupted);
        try {
            crypto.open(altered);
            throw new AssertionError("Corrupção não detetada");
        } catch (GeneralSecurityException expected) {
            // A adulteração tem de ser rejeitada.
        }
        byte[] modifiedSalt = one.salt();
        modifiedSalt[0] ^= 1;
        try {
            crypto.open(new Sealed(one.chunkId(), one.keyVersion(), modifiedSalt, one.nonce(), one.ciphertext()));
            throw new AssertionError("Alteração de salt não detetada");
        } catch (GeneralSecurityException expected) { }
        System.out.println("CRYPTO TESTS OK: round-trip, padding, aleatoriedade e adulteração");
    }
}
