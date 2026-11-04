package rep03;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Análise offline dos carimbos dos líderes, sem modificar os registos de entrada. */
public final class CausalCheck {
    private static final Pattern LEADER = Pattern.compile("lider-p(\\d+)\\.txt");

    private enum Order { BEFORE, AFTER, EQUAL, CONCURRENT }

    private CausalCheck() { }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Uso: CausalCheck <N> <pasta>");
            System.exit(1);
        }
        try {
            System.exit(check(Integer.parseInt(args[0]), Paths.get(args[1]), System.out));
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("ERRO: " + e.getMessage());
            System.exit(1);
        }
    }

    // 0: análise válida; 1: entrada inválida ou violação da condição de Lamport.
    static int check(int n, Path folder, PrintStream out) throws IOException {
        if (n <= 0) {
            throw new IllegalArgumentException("Número de partições inválido: " + n);
        }
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Pasta inexistente: " + folder);
        }
        List<Event> events = read(n, folder, out);
        long before = 0;
        long after = 0;
        long concurrent = 0;
        long equal = 0;
        long differentLamport = 0;
        long lamportErrors = 0;
        Event exampleA = null;
        Event exampleB = null;

        out.println("PARES ENTRE PARTIÇÕES DIFERENTES:");
        for (int i = 0; i < events.size(); i++) {
            Event a = events.get(i);
            for (int j = i + 1; j < events.size(); j++) {
                Event b = events.get(j);
                if (a.partition == b.partition) continue;
                Order order = compare(a.vector, b.vector);
                if (order == Order.BEFORE) {
                    before++;
                    out.println(a.id() + " -> " + b.id());
                    if (!validLamport(a, b, out)) lamportErrors++;
                } else if (order == Order.AFTER) {
                    after++;
                    out.println(b.id() + " -> " + a.id());
                    if (!validLamport(b, a, out)) lamportErrors++;
                } else {
                    // Sem ordem estrita: inclui os iguais, assinalados separadamente.
                    concurrent++;
                    if (order == Order.EQUAL) equal++;
                    out.println(a.id() + " || " + b.id()
                            + (order == Order.EQUAL ? " (vetores iguais; sem causalidade estrita)" : ""));
                    if (a.record.getLamport() != b.record.getLamport()) {
                        differentLamport++;
                        if (exampleA == null) {
                            exampleA = a;
                            exampleB = b;
                        }
                    }
                }
            }
        }

        out.println("eventos=" + events.size());
        out.println("aAntesB=" + before);
        out.println("bAntesA=" + after);
        out.println("paresCausais=" + (before + after));
        out.println("paresConcorrentes=" + concurrent);
        out.println("paresVetoresIguais=" + equal);
        out.println("errosLamport=" + lamportErrors);
        out.println("paresConcorrentesLamportDistinto=" + differentLamport);
        out.printf(Locale.ROOT, "proporcaoConcorrentesLamportDistinto=%.6f%n",
                concurrent == 0 ? 0.0 : (double) differentLamport / concurrent);
        if (exampleA != null) {
            out.println("EXEMPLO: " + exampleA.id() + " || " + exampleB.id()
                    + "; L=" + exampleA.record.getLamport() + " e " + exampleB.record.getLamport()
                    + "; V=" + exampleA.vector + " e " + exampleB.vector);
        }

        List<Event> ordered = new ArrayList<>(events);
        ordered.sort(Comparator.comparingLong((Event event) -> event.record.getLamport())
                .thenComparingInt(event -> event.partition));
        List<String> view = new ArrayList<>();
        out.println("ORDEM TOTAL (L,k), SEM INFERIR CAUSALIDADE:");
        for (Event event : ordered) {
            // A partição identifica a origem; a linha do SensorRecord conserva os seis campos.
            String line = "p" + event.partition + ";" + event.record.toLine();
            view.add(line);
            out.println("(" + event.record.getLamport() + "," + event.partition + ") " + line);
        }
        Path target = folder.resolve("vista-lamport.txt");
        Files.write(target, view, StandardCharsets.UTF_8);
        out.println("vistaLamport=" + target);
        out.println(lamportErrors == 0 ? "RESULTADO: VÁLIDO." : "RESULTADO: FALHOU.");
        return lamportErrors == 0 ? 0 : 1;
    }

    private static List<Event> read(int n, Path folder, PrintStream out) throws IOException {
        List<Path> paths;
        try (Stream<Path> files = Files.list(folder)) {
            paths = files.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .collect(Collectors.toList());
        }
        List<Event> events = new ArrayList<>();
        Set<Integer> partitions = new HashSet<>();
        for (Path path : paths) {
            String name = path.getFileName().toString();
            Matcher matcher = LEADER.matcher(name);
            if (!matcher.matches()) {
                if (name.startsWith("lider-p") && name.endsWith(".txt")) {
                    throw new IllegalArgumentException("Nome de ficheiro inválido: " + name);
                }
                continue;
            }
            int k = Integer.parseInt(matcher.group(1));
            if (k >= n) {
                throw new IllegalArgumentException("Partição fora de 0.." + (n - 1) + ": " + name);
            }
            if (!partitions.add(k)) {
                throw new IllegalArgumentException("Mais de um ficheiro de líder para p" + k + ": " + name);
            }
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Não é um ficheiro regular: " + path);
            }
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                try {
                    SensorRecord record = SensorRecord.fromLine(lines.get(i));
                    VectorClock vector = record.getVector();
                    if (vector.size() != n) {
                        throw new IllegalArgumentException("Dimensão vetorial " + vector.size()
                                + ", esperado N=" + n);
                    }
                    if (vector.get(k) != record.getSeq()) {
                        throw new IllegalArgumentException("seq=" + record.getSeq()
                                + " tem V[" + k + "]=" + vector.get(k));
                    }
                    events.add(new Event(k, record, vector, name + ":" + (i + 1)));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("p" + k + " " + name
                            + ", linha " + (i + 1) + ": " + e.getMessage(), e);
                }
            }
        }
        if (partitions.isEmpty()) {
            throw new IllegalArgumentException("Nenhum ficheiro lider-p*.txt em " + folder);
        }
        out.println("ficheirosLider=" + partitions.size() + " N=" + n);
        return events;
    }

    private static Order compare(VectorClock a, VectorClock b) {
        boolean less = false;
        boolean greater = false;
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) < b.get(i)) less = true;
            if (a.get(i) > b.get(i)) greater = true;
        }
        if (less && greater) return Order.CONCURRENT;
        if (less) return Order.BEFORE;
        if (greater) return Order.AFTER;
        return Order.EQUAL;
    }

    private static boolean validLamport(Event a, Event b, PrintStream out) {
        if (a.record.getLamport() < b.record.getLamport()) return true;
        out.println("ERRO LAMPORT: " + a.id() + " [" + a.origin + "] -> "
                + b.id() + " [" + b.origin + "], mas L=" + a.record.getLamport()
                + " >= " + b.record.getLamport());
        return false;
    }

    private static final class Event {
        final int partition;
        final SensorRecord record;
        final VectorClock vector;
        final String origin;

        Event(int partition, SensorRecord record, VectorClock vector, String origin) {
            this.partition = partition;
            this.record = record;
            this.vector = vector;
            this.origin = origin;
        }

        String id() {
            return "p" + partition + "#" + record.getSeq();
        }
    }
}
