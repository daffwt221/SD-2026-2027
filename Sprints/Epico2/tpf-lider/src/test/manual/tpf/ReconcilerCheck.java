package tpf;

/** Teste simples dos limiares do registo usados pelo Reconciler. */
public final class ReconcilerCheck {
    private static void ok(boolean condition) {
        if (!condition) throw new AssertionError("Falha na verificação do Reconciler");
    }

    public static void main(String[] args) {
        ReplicaRegistry reg = new ReplicaRegistry();
        reg.heartbeat("B", 2097152, 0, 1000);
        ok(reg.snapshot(1000).get(0).state() == ReplicaRegistry.State.ATIVA);
        reg.disconnected("B");
        ok(reg.snapshot(1001).get(0).state() == ReplicaRegistry.State.SUSPEITA);
        ok(reg.snapshot(31000).get(0).state() == ReplicaRegistry.State.MORTA);
        reg.heartbeat("B", 2097152, 0, 32000);
        ok(reg.snapshot(32000).get(0).state() == ReplicaRegistry.State.ATIVA);
        ok(Reconciler.MAX_TASKS_PER_ROUND == 20);
        ok(Reconciler.INTERVAL_SECONDS == 5);
        System.out.println("RECONCILER CHECK OK: estados, reconexao, 5s e limite 20");
    }
}
