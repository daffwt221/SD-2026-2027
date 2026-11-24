package tpf;

/** Parâmetros locais do sprint E2-02. Podem ser alterados com -Dtpf.*. */
public final class Wire {
    private Wire() { }
    public static final int CHUNK_MAX_BYTES = inteiro("tpf.chunk.max.bytes", 65_536);
    public static final int PAD_BYTES = inteiro("tpf.pad.bytes", 4_096);
    public static final long SELAGEM_MS = longo("tpf.selagem.ms", 5_000);

    private static int inteiro(String nome, int padrao) {
        int n = Integer.getInteger(nome, padrao);
        if (n < 1 || n > 16_777_216) throw new IllegalArgumentException(nome + " inválido");
        return n;
    }
    private static long longo(String nome, long padrao) {
        long n = Long.getLong(nome, padrao);
        if (n < 1 || n > 86_400_000) throw new IllegalArgumentException(nome + " inválido");
        return n;
    }
}
