package rep03;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Verificação offline; executar depois de parar as escritas e reunir os ficheiros. */
public final class PartitionCheck {
    private static final int REPLICAS_ESPERADAS = 2;
    private static final Pattern LEADER = Pattern.compile("lider-p(\\d+)\\.txt");
    private static final Pattern REPLICA = Pattern.compile("replica-(\\d+)-p(\\d+)\\.txt");

    private PartitionCheck() { }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Uso: PartitionCheck <N> <pasta>");
            System.exit(1);
        }
        try {
            System.exit(check(Integer.parseInt(args[0]), Paths.get(args[1]), System.out));
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("ERRO: " + e.getMessage());
            System.exit(1);
        }
    }

    // 0: iguais; 1: erro; 2: prefixos válidos, mas ainda sem convergência.
    static int check(int n, Path folder, PrintStream out) throws IOException {
        Partitioner partitioner = new Partitioner(n);
        if (!Files.isDirectory(folder)) {
            throw new IllegalArgumentException("Pasta inexistente: " + folder);
        }
        Map<Integer, Path> leaders = new HashMap<>();
        Map<Integer, List<Path>> replicas = new HashMap<>();
        Map<Integer, Set<Integer>> replicaIds = new HashMap<>();
        boolean error = false;
        boolean behind = false;
        List<Path> paths;
        try (Stream<Path> files = Files.list(folder)) {
            paths = files.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        }
        for (Path path : paths) {
            String name = path.getFileName().toString();
            Matcher leader = LEADER.matcher(name);
            Matcher replica = REPLICA.matcher(name);
            boolean isLeader = leader.matches();
            boolean isReplica = replica.matches();
            if (!isLeader && !isReplica) {
                if ((name.startsWith("lider-p") || name.startsWith("replica-"))
                        && name.endsWith(".txt")) {
                    out.println("ERRO: nome de ficheiro inválido: " + name);
                    error = true;
                }
                continue;
            }
            if (!Files.isRegularFile(path)) {
                out.println("ERRO: não é um ficheiro regular: " + name);
                error = true;
                continue;
            }
            try {
                int k = Integer.parseInt(isLeader ? leader.group(1) : replica.group(2));
                if (k < 0 || k >= n) {
                    out.println("ERRO: partição fora de 0.." + (n - 1) + ": " + name);
                    error = true;
                    continue;
                }
                if (isLeader) {
                    if (leaders.putIfAbsent(k, path) != null) {
                        out.println("ERRO: mais de um ficheiro de líder para p" + k + ": " + name);
                        error = true;
                    }
                } else {
                    int id = Integer.parseInt(replica.group(1));
                    if (id < 1) {
                        throw new IllegalArgumentException("id da réplica deve ser positivo");
                    }
                    if (!replicaIds.computeIfAbsent(k, ignored -> new HashSet<>()).add(id)) {
                        out.println("ERRO: id de réplica repetido em p" + k + ": " + name);
                        error = true;
                    }
                    replicas.computeIfAbsent(k, ignored -> new ArrayList<>()).add(path);
                }
            } catch (IllegalArgumentException e) {
                out.println("ERRO: nome de ficheiro inválido: " + name + " (" + e.getMessage() + ")");
                error = true;
            }
        }

        long total = 0;
        List<Long> partitionCounts = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            out.println("PARTIÇÃO p" + k);
            Path leaderPath = leaders.get(k);
            FileData source = null;
            if (leaderPath == null) {
                out.println("ERRO: líder ausente: lider-p" + k + ".txt");
                error = true;
            } else {
                source = read(leaderPath, k, partitioner, true, out);
                error |= !source.valid;
                out.println("  líder: registos=" + source.lines.size()
                        + ", sensores distintos=" + source.sensors.size());
                total += source.lines.size();
            }
            partitionCounts.add(source == null ? 0L : (long) source.lines.size());
            int copies = replicaIds.getOrDefault(k, new HashSet<>()).size();
            out.println("  réplicas=" + copies + " (esperado R=" + REPLICAS_ESPERADAS + ")");
            if (copies != REPLICAS_ESPERADAS) {
                out.println("ERRO: R incorreto em p" + k);
                error = true;
            }
            for (Path replicaPath : replicas.getOrDefault(k, new ArrayList<>())) {
                FileData copy = read(replicaPath, k, partitioner, false, out);
                error |= !copy.valid;
                String name = replicaPath.getFileName().toString();
                out.println("  " + name + ": registos=" + copy.lines.size()
                        + ", sensores distintos=" + copy.sensors.size());
                if (source == null) {
                    out.println("ERRO: réplica sem líder para comparação: " + name);
                    error = true;
                    continue;
                }
                if (!source.valid || !copy.valid) {
                    out.println("  INVÁLIDO: comparação não certificada para " + name);
                    continue;
                }
                int common = 0;
                int limit = Math.min(source.lines.size(), copy.lines.size());
                while (common < limit && source.lines.get(common).equals(copy.lines.get(common))) {
                    common++;
                }
                if (common != limit || copy.lines.size() > source.lines.size()) {
                    out.println("ERRO: DIVERGENTE: " + name + ", primeira diferença na linha "
                            + (common + 1) + "; faltas não inferíveis de uma divergência");
                    error = true;
                } else {
                    int missing = source.lines.size() - copy.lines.size();
                    out.println("  " + (missing == 0 ? "IGUAL" : "PREFIXO (atraso, sem corrupção)")
                            + ": " + name + ", em falta=" + missing);
                    behind |= missing > 0;
                }
            }
        }
        long sum = partitionCounts.stream().mapToLong(Long::longValue).sum();
        out.println("TOTAL DO SISTEMA (só líderes, sem contar cópias): " + total);
        out.println("SOMA DAS PARTIÇÕES: " + sum + (total == sum ? " (igual ao total)" : " (ERRO)"));
        error |= total != sum;
        if (error) {
            out.println("RESULTADO: FALHOU; totais apenas informativos, há dados/configuração inválidos.");
            return 1;
        }
        if (behind) {
            out.println("RESULTADO: SEM CORRUPÇÃO, MAS AINDA NÃO CONVERGIU.");
            return 2;
        }
        out.println("RESULTADO: VÁLIDO E CONVERGENTE.");
        return 0;
    }

    private static FileData read(Path path, int k, Partitioner partitioner,
                                 boolean leader, PrintStream out) throws IOException {
        FileData data = new FileData(Files.readAllLines(path, StandardCharsets.UTF_8));
        for (int i = 0; i < data.lines.size(); i++) {
            String line = data.lines.get(i);
            try {
                // Valida os seis campos, incluindo Lamport e componentes vetoriais.
                SensorRecord record = SensorRecord.fromLine(line);
                data.sensors.add(record.getSensorId());
                if (record.getSeq() != (long) i + 1) {
                    throw new IllegalArgumentException("sequência: esperado " + ((long) i + 1)
                            + ", encontrado " + record.getSeq());
                }
                VectorClock vector = record.getVector();
                if (k >= vector.size()) {
                    throw new IllegalArgumentException("seq=" + record.getSeq()
                            + " tem vetor sem componente V[" + k + "]");
                }
                if (vector.get(k) != record.getSeq()) {
                    throw new IllegalArgumentException("seq=" + record.getSeq()
                            + " tem V[" + k + "]=" + vector.get(k));
                }
                if (leader && partitioner.of(record.getSensorId()) != k) {
                    throw new IllegalArgumentException("sensor " + record.getSensorId()
                            + " pertence a p" + partitioner.of(record.getSensorId()));
                }
            } catch (IllegalArgumentException e) {
                out.println("ERRO: p" + k + " " + path.getFileName()
                        + ", linha " + (i + 1) + ": " + e.getMessage());
                data.valid = false;
            }
        }
        return data;
    }

    private static final class FileData {
        final List<String> lines;
        final Set<String> sensors = new HashSet<>();
        boolean valid = true;

        FileData(List<String> lines) {
            this.lines = lines;
        }
    }
}
