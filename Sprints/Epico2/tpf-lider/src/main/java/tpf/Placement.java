package tpf;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Rendezvous hashing ponderado por espaço livre (alpha = 1). */
public final class Placement {
    private Placement() {}
    public record Candidate(String id, long free) {}

    public static List<String> choose(String chunkId, long size, long margin,
                                      int replicas, List<Candidate> candidates, Set<String> exclude) {
        if (size < 0 || margin < 0 || replicas < 0) throw new IllegalArgumentException("Parâmetros inválidos");
        record Scored(String id, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (Candidate r : candidates) {
            if (r.free() < size + margin || exclude.contains(r.id())) continue;
            // Derivação estável de u em ]0,1[ pelos 53 bits mais significativos do SHA-256.
            byte[] digest;
            try {
                digest = MessageDigest.getInstance("SHA-256").digest(
                    (chunkId + ":" + r.id()).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { throw new IllegalStateException(e); }
            long n = 0;
            for (int i = 0; i < 7; i++) n = (n << 8) | (digest[i] & 0xffL);
            long bits = n >>> 3; // 53 bits
            double u = (bits + 0.5d) / 9007199254740992.0d;
            // Comparar log(score) evita problemas numéricos para quotas grandes.
            double score = Math.log((double) r.free()) - Math.log(-Math.log(u));
            scored.add(new Scored(r.id(), score));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(Scored::id));
        return scored.stream().limit(replicas).map(Scored::id).toList();
    }
}
