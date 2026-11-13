package raft04;

/** Formato de VOTO definido na ficha; S concede e N recusa. */
public final class VoteReply {
    private final long term;
    private final int voter;
    private final int candidate;
    private final boolean granted;

    public VoteReply(long term, int voter, int candidate, boolean granted) {
        if (term < 0 || voter < 1 || voter > Wire.GRUPO || candidate < 1 || candidate > Wire.GRUPO) {
            throw new IllegalArgumentException("VOTO inválido");
        }
        this.term = term;
        this.voter = voter;
        this.candidate = candidate;
        this.granted = granted;
    }

    public String encode() {
        return "VOTO;" + term + ";" + voter + ";" + candidate + ";" + (granted ? "S" : "N");
    }

    public static VoteReply decode(String message) {
        if (message == null) throw new IllegalArgumentException("VOTO ausente");
        String[] fields = message.strip().split(";", -1);
        if (fields.length != 5 || !fields[0].equals("VOTO")
                || !(fields[4].equals("S") || fields[4].equals("N"))) {
            throw new IllegalArgumentException("VOTO mal formado");
        }
        return new VoteReply(Long.parseLong(fields[1]), Integer.parseInt(fields[2]),
                Integer.parseInt(fields[3]), fields[4].equals("S"));
    }

    public long getTerm() { return term; }
    public int getVoter() { return voter; }
    public int getCandidate() { return candidate; }
    public boolean isGranted() { return granted; }
}
