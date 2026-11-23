package tpf;

import java.util.List;
import java.util.Set;

public final class PlacementCheck {
    public static void main(String[] args) {
        var nodes = List.of(new Placement.Candidate("A", 8_000_000),
            new Placement.Candidate("B", 2_000_000), new Placement.Candidate("C", 4_000_000));
        var a = Placement.choose("abc", 4112, 4096, 3, nodes, Set.of());
        if (a.size() != 3 || !a.equals(Placement.choose("abc", 4112, 4096, 3, nodes, Set.of())))
            throw new AssertionError("Placement não determinístico");
        var b = Placement.choose("abc", 4112, 4096, 3, nodes, Set.of("B"));
        if (b.size() != 2 || b.contains("B")) throw new AssertionError("Exclusão inválida");
        var c = Placement.choose("abc", 2_000_000, 4096, 3, nodes, Set.of());
        if (c.contains("B")) throw new AssertionError("Candidato sem quota foi escolhido");
        System.out.println("PLACEMENT TESTS OK: determinismo, exclusão, quota e R=3");
    }
}
