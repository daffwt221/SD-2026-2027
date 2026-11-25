package tpf;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

public final class CryptoCheck {
    private static void rejeita(ChunkCrypto crypto, Sealed chunk, String caso) throws Exception {
        try { crypto.open(chunk); throw new AssertionError("Aceitou " + caso); }
        catch (GeneralSecurityException expected) { }
    }
    public static void main(String[] args) throws Exception {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        ChunkCrypto crypto = new ChunkCrypto(key);
        for (int n : new int[]{0,1,4091,4092,4096,65536}) {
            byte[] b=new byte[n]; new SecureRandom().nextBytes(b);
            Sealed s=crypto.seal(b);
            if (!Arrays.equals(b,crypto.open(s))) throw new AssertionError("round-trip " + n);
            if ((s.ciphertext().length-16)%Wire.PAD_BYTES!=0) throw new AssertionError("padding " + n);
        }
        Sealed s=crypto.seal("Exemplo de teste".getBytes(StandardCharsets.UTF_8));
        Sealed s2=crypto.seal("Exemplo de teste".getBytes(StandardCharsets.UTF_8));
        if (s.chunkId().equals(s2.chunkId())) throw new AssertionError("Aleatoriedade em falta");
        byte[] ct=s.ciphertext(); ct[0]^=1;
        rejeita(crypto,new Sealed(s.chunkId(),s.keyVersion(),s.salt(),s.nonce(),ct),"ciphertext alterado");
        byte[] salt=s.salt();salt[0]^=1;
        rejeita(crypto,new Sealed(s.chunkId(),s.keyVersion(),salt,s.nonce(),s.ciphertext()),"salt alterado");
        byte[] nonce=s.nonce();nonce[0]^=1;
        rejeita(crypto,new Sealed(s.chunkId(),s.keyVersion(),s.salt(),nonce,s.ciphertext()),"nonce alterado");
        rejeita(crypto,new Sealed(s.chunkId(),2,s.salt(),s.nonce(),s.ciphertext()),"versão alterada");
        byte[] outra=key.clone(); outra[0]^=1;
        rejeita(new ChunkCrypto(outra),s,"master.key diferente");
        System.out.println("CRYPTO CHECK OK: round-trip, padding, salt, nonce, versão, master.key e corrupção");
    }
}
