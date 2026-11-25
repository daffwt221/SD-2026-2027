package tpf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Serialização binária UTF-8 de linhas Registo; o padding é aplicado por ChunkCrypto. */
public final class Chunk {
    private Chunk() { }
    public static byte[] serializar(List<Registo> registos) {
        StringBuilder sb = new StringBuilder();
        for (Registo r : registos) sb.append(r.serializar());
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
    public static List<Registo> ler(byte[] plaintext) {
        String texto = new String(plaintext, StandardCharsets.UTF_8);
        if (!texto.isEmpty() && !texto.endsWith("\n")) throw new IllegalArgumentException("Chunk incompleto");
        List<Registo> registos = new ArrayList<>();
        for (String linha : texto.split("\n")) if (!linha.isEmpty()) registos.add(Registo.ler(linha));
        return List.copyOf(registos);
    }
}
