package raft04;

/** Formato de PEDIDO_VOTO definido na ficha. */
public final class VoteRequest {
    private final long term;
    private final int candidate;
    private final long lastSeq;
    private final long lastTerm;

    public VoteRequest(long term, int candidate, long lastSeq, long lastTerm) {
        if (term < 1 || candidate < 1 || candidate > Wire.GRUPO || lastSeq < 0
                || lastTerm < 0 || lastTerm > term || ((lastSeq == 0) != (lastTerm == 0))) {
            throw new IllegalArgumentException("PEDIDO_VOTO inválido");
        }
        this.term = term;
        this.candidate = candidate;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
    }

    public String encode() {
        return "PEDIDO_VOTO;" + term + ";" + candidate + ";" + lastSeq + ";" + lastTerm;
    }

    public static VoteRequest decode(String message) {
        if (message == null) throw new IllegalArgumentException("PEDIDO_VOTO ausente");
        String[] fields = message.strip().split(";", -1);
        if (fields.length != 5 || !fields[0].equals("PEDIDO_VOTO")) {
            throw new IllegalArgumentException("PEDIDO_VOTO mal formado");
        }
        return new VoteRequest(Long.parseLong(fields[1]), Integer.parseInt(fields[2]),
                Long.parseLong(fields[3]), Long.parseLong(fields[4]));
    }

    public long getTerm() { return term; }
    public int getCandidate() { return candidate; }
    public long getLastSeq() { return lastSeq; }
    public long getLastTerm() { return lastTerm; }
}
