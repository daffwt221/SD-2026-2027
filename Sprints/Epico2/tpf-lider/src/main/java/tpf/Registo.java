package tpf;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/** Registo durável com os cinco campos exigidos na issue #21. */
public record Registo(long lsn, Instant instante, String autor, String canal, String texto) {
    public Registo {
        if (lsn < 1 || instante == null || autor == null || canal == null || texto == null)
            throw new IllegalArgumentException("Registo inválido");
    }
    public String serializar() {
        return lsn + ";" + instante + ";" + autor + ";" + canal + ";"
                + Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8)) + "\n";
    }
    public static Registo ler(String linha) {
        String[] c = linha.split(";", 5);
        if (c.length != 5) throw new IllegalArgumentException("Linha de registo inválida");
        return new Registo(Long.parseLong(c[0]), Instant.parse(c[1]), c[2], c[3],
                new String(Base64.getDecoder().decode(c[4]), StandardCharsets.UTF_8));
    }
}
