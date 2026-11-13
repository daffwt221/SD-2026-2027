package raft04;

import java.util.function.Consumer;

/** Observador de heartbeats do produtor/processador; não participa nas eleições. */
public final class HeartbeatMonitor {
    public enum State { DESCONHECIDO, ATIVO, SUSPEITO }
    private final int partition;
    private final long timeoutNanos;
    private final Consumer<String> log;
    private State state = State.DESCONHECIDO;
    private long lastNanos = System.nanoTime();
    private long term = -1;
    private int leader;
    private long hb;

    public HeartbeatMonitor(int partition, long periodMs, int factor, Consumer<String> log) {
        this.partition = partition;
        timeoutNanos = Math.multiplyExact(Math.multiplyExact(periodMs, factor), 1_000_000L);
        this.log = log;
    }

    public synchronized boolean onHeartbeat(long newTerm, int newLeader, long newHb) {
        if (newTerm < term) return false;
        boolean changed = newTerm > term;
        if (!changed && newLeader != leader) {
            log.accept("ERRO;p" + partition + ";dois lideres no termo=" + term);
            return false;
        }
        if (!changed && newHb <= hb) return false;
        if (changed) {
            log.accept("NOVO_LIDER;p" + partition + ";termo=" + newTerm + ";lider=n" + newLeader);
        } else if (newHb > hb + 1) {
            log.accept("HB_PERDIDO;p" + partition + ";termo=" + term
                    + ";esperado=" + (hb + 1) + ";recebido=" + newHb);
        }
        if (state == State.SUSPEITO)
            log.accept("RETOMA;p" + partition + ";termo=" + newTerm + ";lider=n" + newLeader);
        term = newTerm;
        leader = newLeader;
        hb = newHb;
        lastNanos = System.nanoTime();
        state = State.ATIVO;
        return true;
    }

    public synchronized void check() {
        long elapsed = System.nanoTime() - lastNanos;
        if (state != State.SUSPEITO && elapsed > timeoutNanos) {
            state = State.SUSPEITO;
            log.accept("SUSPEITA;p" + partition + ";termo=" + term + ";lider=n" + leader
                    + ";semHeartbeatMs=" + elapsed / 1_000_000);
        }
    }
    public synchronized State state() { return state; }
    public synchronized long term() { return term; }
}
