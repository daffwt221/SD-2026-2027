package tpf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Estado em memória calculado exclusivamente a partir de heartbeats locais do líder. */
public final class ReplicaRegistry {
    public enum State { ATIVA, SUSPEITA, MORTA }
    public record Status(String id, long quota, long used, long lastHeartbeat,
                         State state, boolean connected) {}
    private static final class Entry {
        long quota, used, lastHeartbeat;
        boolean connected = true;
    }
    private final Map<String, Entry> entries = new HashMap<>();
    private final long suspectMs, deadMs;
    public ReplicaRegistry() { this(6000, 30000); }
    public ReplicaRegistry(long suspectMs, long deadMs) {
        if (suspectMs <= 0 || deadMs <= suspectMs) throw new IllegalArgumentException("Tempos inválidos");
        this.suspectMs = suspectMs; this.deadMs = deadMs;
    }
    public synchronized void heartbeat(String id, long quota, long used, long nowMs) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,40}") || quota < 0 || used < 0 || used > quota)
            throw new IllegalArgumentException("Heartbeat inválido");
        Entry e = entries.computeIfAbsent(id, ignored -> new Entry());
        e.quota = quota; e.used = used; e.lastHeartbeat = nowMs; e.connected = true;
    }
    public synchronized void disconnected(String id) {
        Entry e = entries.get(id);
        if (e != null) e.connected = false;
    }
    private State state(Entry e, long nowMs) {
        long age = Math.max(0, nowMs - e.lastHeartbeat);
        if (age >= deadMs) return State.MORTA;
        if (age >= suspectMs || !e.connected) return State.SUSPEITA;
        return State.ATIVA;
    }
    public synchronized List<Status> snapshot(long nowMs) {
        List<Status> out = new ArrayList<>();
        for (var kv : entries.entrySet()) {
            Entry e = kv.getValue();
            out.add(new Status(kv.getKey(), e.quota, e.used, e.lastHeartbeat,
                state(e, nowMs), e.connected));
        }
        out.sort(java.util.Comparator.comparing(Status::id));
        return List.copyOf(out);
    }
}
