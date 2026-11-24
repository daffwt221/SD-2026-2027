package tpf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class CatalogRegistryCheck {
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("tpf-catalog-check-");
        String id = "a".repeat(64);
        Path hdr = folder.resolve(id + ".hdr");
        Properties p = new Properties();
        p.setProperty("lsnDe", "1"); p.setProperty("lsnAte", "4"); p.setProperty("keyVersion", "1");
        try (var out = Files.newOutputStream(hdr)) { p.store(out, "test"); }
        Path file = folder.resolve("catalogo.log");
        Catalog first = new Catalog(file);
        first.recordAck(id, "A", hdr);
        first.recordAck(id, "A", hdr);
        first.recordAck(id, "B", hdr);
        Catalog afterRestart = new Catalog(file);
        var entries = afterRestart.snapshot();
        if (entries.size() != 1 || entries.get(0).lsnDe() != 1 || entries.get(0).lsnAte() != 4 ||
            !entries.get(0).replicasHistoricas().equals(java.util.Set.of("A", "B")))
            throw new AssertionError("Catálogo não recuperou os ACKs históricos");
        if (Files.readAllLines(file).size() != 2) throw new AssertionError("ACK duplicado");

        ReplicaRegistry registry = new ReplicaRegistry();
        registry.heartbeat("A", 10000, 100, 1000);
        assertState(registry, 2000, ReplicaRegistry.State.ATIVA);
        assertState(registry, 7000, ReplicaRegistry.State.SUSPEITA);
        assertState(registry, 31000, ReplicaRegistry.State.MORTA);
        registry.heartbeat("A", 10000, 150, 32000);
        assertState(registry, 33000, ReplicaRegistry.State.ATIVA);
        registry.disconnected("A");
        assertState(registry, 33000, ReplicaRegistry.State.SUSPEITA);
        System.out.println("CATALOG/REGISTRY TESTS OK: persistência, reinício, duplicados, 6s/30s e reconexão");
    }
    private static void assertState(ReplicaRegistry r, long t, ReplicaRegistry.State expected) {
        if (r.snapshot(t).get(0).state() != expected) throw new AssertionError("Estado esperado: " + expected);
    }
}
