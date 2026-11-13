package rep04;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Análise offline: não altera os ficheiros recebidos nem inventa instantes de falha. */
public final class HeartbeatCheck {
    private static final Pattern HB_FILE = Pattern.compile("hb-p(\\d+)\\.txt");
    private static final Pattern LEADER_FILE = Pattern.compile("lider-p(\\d+)\\.txt");
    private static final Pattern MONITOR_FILE = Pattern.compile("monitor-(.+)\\.txt");
    private final int n;
    private final Path folder;
    private final PrintStream out;
    private final ZoneId zone = ZoneId.systemDefault();
    private final Map<Integer, List<Heartbeat>> heartbeats = new TreeMap<>();
    private final Map<Integer, NavigableMap<Long, RecordInfo>> records = new TreeMap<>();
    private final Map<String, Group> groups = new TreeMap<>();
    private final Map<String, Reception> receptions = new HashMap<>();
    private final Set<String> warnings = new LinkedHashSet<>();
    private final List<Failure> failures = new ArrayList<>();
    private int recognizedFiles;

    private HeartbeatCheck(int n, Path folder, PrintStream out) {
        if (n < 1 || !Files.isDirectory(folder)) throw new IllegalArgumentException("N ou pasta inválido");
        this.n = n;
        this.folder = folder;
        this.out = out;
        for (int k = 0; k < n; k++) {
            heartbeats.put(k, new ArrayList<>());
            records.put(k, new TreeMap<>());
        }
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.err.println("Uso: HeartbeatCheck <N> <pasta> [falhas.txt]");
            System.exit(1);
        }
        try {
            Path folder = Path.of(args[1]);
            Path failures = args.length == 3 ? Path.of(args[2]) : null;
            if (failures != null && !failures.isAbsolute() && !Files.exists(failures)) {
                failures = folder.resolve(failures);
            }
            System.exit(check(Integer.parseInt(args[0]), folder, failures, System.out));
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("ERRO: " + e.getMessage());
            System.exit(1);
        }
    }

    static int check(int n, Path folder, Path failureFile, PrintStream out) throws IOException {
        HeartbeatCheck report = new HeartbeatCheck(n, folder, out);
        report.readFiles();
        if (failureFile != null) report.readFailures(failureFile);
        report.analyzeMonitors();
        report.printBatches();
        report.printSuspicions();
        if (failureFile != null) report.printDetection();
        else out.println("DETECAO: sem falhas.txt; instantes de falha e tempos de deteção não inferidos.");
        report.printLatency();
        report.printBytes();
        for (String warning : report.warnings) out.println("AVISO: " + warning);
        out.println("FIM;avisos=" + report.warnings.size());
        return 0;
    }

    private interface LineReader { void read(String line); }

    private void readLines(Path file, LineReader reader) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.isBlank()) continue;
            try { reader.read(line); }
            catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(file.getFileName() + ":" + (i + 1) + ": " + e.getMessage(), e);
            }
        }
    }

    private int partition(String value) {
        int k = Integer.parseInt(value.startsWith("p") ? value.substring(1) : value);
        if (k < 0 || k >= n) throw new IllegalArgumentException("Partição fora de N: " + value);
        return k;
    }

    private static long nonnegative(String value) {
        long number = Long.parseLong(value);
        if (number < 0) throw new IllegalArgumentException("Valor negativo: " + value);
        return number;
    }

    private static Map<String, String> fields(String[] parts, int from) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = from; i < parts.length; i++) {
            int equal = parts[i].indexOf('=');
            if (equal > 0) values.put(parts[i].substring(0, equal), parts[i].substring(equal + 1));
        }
        return values;
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null) throw new IllegalArgumentException("Campo ausente: " + key);
        return value;
    }

    private void readFiles() throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.list(folder)) {
            files = paths.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        Set<String> published = new HashSet<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            Matcher hb = HB_FILE.matcher(name);
            Matcher leader = LEADER_FILE.matcher(name);
            Matcher monitor = MONITOR_FILE.matcher(name);
            if (hb.matches()) {
                int k = partition(hb.group(1));
                recognizedFiles++;
                readLines(file, line -> {
                    String[] f = line.split(";", -1);
                    if (f.length != 6) throw new IllegalArgumentException("Esperados seis campos de heartbeat");
                    Heartbeat row = new Heartbeat(k, nonnegative(f[0]), nonnegative(f[1]), nonnegative(f[2]),
                            nonnegative(f[3]), Integer.parseInt(f[4]), nonnegative(f[5]));
                    if (row.hb < 1 || row.count < 0 || row.count > row.last || row.bytes < 1) {
                        throw new IllegalArgumentException("Heartbeat inválido");
                    }
                    if (!published.add(row.key())) throw new IllegalArgumentException("Heartbeat repetido no ficheiro");
                    heartbeats.get(k).add(row);
                });
            } else if (leader.matches()) {
                int k = partition(leader.group(1));
                recognizedFiles++;
                readLines(file, line -> {
                    SensorRecord record = SensorRecord.fromLine(line);
                    if (record.getVector().size() != n || record.getVector().get(k) != record.getSeq()
                            || Math.floorMod(record.getSensorId().hashCode(), n) != k) {
                        throw new IllegalArgumentException("Registo incompatível com p" + k);
                    }
                    long time = LocalDateTime.parse(record.getTimestamp()).atZone(zone).toInstant().toEpochMilli();
                    if (records.get(k).putIfAbsent(record.getSeq(), new RecordInfo(time, line)) != null) {
                        throw new IllegalArgumentException("seq repetido no líder");
                    }
                });
            } else if (monitor.matches()) {
                String participant = monitor.group(1);
                recognizedFiles++;
                readLines(file, line -> {
                    String[] f = line.split(";", -1);
                    if (f.length < 3) throw new IllegalArgumentException("Evento de monitor mal formado");
                    int k = partition(f[2]);
                    long time = nonnegative(f[0]);
                    Map<String, String> values = fields(f, 3);
                    if (f[1].equals("RECEBIDO")) new Reception(k, time, values);
                    else if (f[1].equals("RETOMA")) nonnegative(required(values, "inc"));
                    else if (f[1].equals("SUSPEITA") && values.containsKey("silencio_ms")) nonnegative(values.get("silencio_ms"));
                    Group group = groups.computeIfAbsent(participant + ":" + k, ignored -> new Group(participant, k));
                    group.events.add(new Event(time, f[1], values));
                });
            }
        }
        if (recognizedFiles == 0) throw new IllegalArgumentException("Pasta sem ficheiros hb/lider/monitor reconhecidos");
        out.println("HEARTBEATCHECK;N=" + n + ";fuso=" + zone);
        out.println("Classificação observacional: FALHA_REAL significa ausência de retoma válida da mesma incarnação no registo.");
        out.println("Latência: primeira receção por participante/partição/seq; recuperações TCP não são receções de lote.");
    }

    private void readFailures(Path file) throws IOException {
        readLines(file, line -> {
            String[] f = line.split(";", -1);
            if (f.length != 2) throw new IllegalArgumentException("Falha deve ter formato p<k>;instante");
            failures.add(new Failure(partition(f[0]), nonnegative(f[1])));
        });
        failures.sort(Comparator.comparingInt((Failure f) -> f.partition).thenComparingLong(f -> f.time));
    }

    private void analyzeMonitors() {
        for (Group group : groups.values()) {
            group.events.sort(Comparator.comparingLong(e -> e.time));
            long inc = -1;
            long hb = 0;
            Suspicion pending = null;
            for (Event event : group.events) {
                if (event.type.equals("SUSPEITA")) {
                    Long observedSilence = event.fields.containsKey("silencio_ms")
                            ? nonnegative(event.fields.get("silencio_ms")) : null;
                    pending = new Suspicion(event.time, inc, observedSilence);
                    group.suspicions.add(pending);
                } else if (event.type.equals("RETOMA")) {
                    long resumed = nonnegative(required(event.fields, "inc"));
                    if (pending != null) {
                        pending.falseSuspicion = pending.inc >= 0 && pending.inc == resumed;
                        pending = null;
                    }
                } else if (event.type.equals("RECEBIDO")) {
                    Reception received = new Reception(group.partition, event.time, event.fields);
                    group.receptions.add(received);
                    Reception previous = receptions.get(received.key());
                    if (previous == null || (previous.headerOnly && !received.headerOnly)) {
                        receptions.put(received.key(), received);
                    }
                    if (received.inc > inc || (received.inc == inc && received.hb > hb)) {
                        if (pending != null) {
                            pending.falseSuspicion = pending.inc >= 0 && pending.inc == received.inc;
                            pending = null;
                        }
                        inc = received.inc;
                        hb = received.hb;
                    }
                } else if (event.type.equals("HB_PERDIDO")) group.lost++;
                else if (event.type.equals("RECUPERACAO")) group.recovered++;
            }
        }
    }

    private void printBatches() {
        for (int k = 0; k < n; k++) {
            Map<Long, List<Heartbeat>> incarnations = new TreeMap<>();
            for (Heartbeat hb : heartbeats.get(k)) incarnations.computeIfAbsent(hb.inc, ignored -> new ArrayList<>()).add(hb);
            if (incarnations.isEmpty()) warnings.add("Sem heartbeats publicados de p" + k);
            for (Map.Entry<Long, List<Heartbeat>> entry : incarnations.entrySet()) {
                List<Heartbeat> rows = entry.getValue();
                rows.sort(Comparator.comparingLong(row -> row.hb));
                Stats sizes = new Stats();
                Stats intervals = new Stats();
                long empty = 0;
                Heartbeat previous = null;
                for (Heartbeat row : rows) {
                    sizes.add(row.count);
                    if (row.count == 0) empty++;
                    if (previous != null) {
                        long interval = row.time - previous.time;
                        intervals.add(interval);
                        if (interval < 0) warnings.add("Instantes de publicação recuam em p" + k + " inc=" + row.inc);
                    }
                    previous = row;
                }
                out.println("LOTES;p=" + k + ";inc=" + entry.getKey() + ";lotes=" + rows.size()
                        + ";vazios=" + empty + ";proporcaoVazios=" + number((double) empty / rows.size())
                        + ";registosMedia=" + sizes.mean() + ";registosMax=" + sizes.maximum()
                        + ";intervaloMediaMs=" + intervals.mean() + ";intervaloMaxMs=" + intervals.maximum());
            }
        }
    }

    private void printSuspicions() {
        long lost = 0;
        long recovered = 0;
        for (Group group : groups.values()) {
            long real = 0, falseCount = 0, unknown = 0;
            for (Suspicion suspicion : group.suspicions) {
                if (suspicion.inc < 0) unknown++;
                else if (suspicion.falseSuspicion) falseCount++;
                else real++;
                Heartbeat last = suspicion.inc < 0 ? null : latestPublished(group.partition, suspicion.time, suspicion.inc);
                out.println("SUSPEITA;participante=" + group.participant + ";p=" + group.partition
                        + ";instante=" + suspicion.time + ";inc=" + (suspicion.inc < 0 ? "NA" : suspicion.inc)
                        + ";classe=" + suspicion.classification() + ";silencioPublicadoMs="
                        + (last == null ? "NA" : suspicion.time - last.time)
                        + ";silencioObservadoMs=" + (suspicion.observedSilence == null ? "NA" : suspicion.observedSilence));
            }
            out.println("MONITOR;participante=" + group.participant + ";p=" + group.partition + ";reais=" + real
                    + ";falsas=" + falseCount + ";indeterminadas=" + unknown
                    + ";hbPerdidos=" + group.lost + ";recuperacoes=" + group.recovered);
            lost += group.lost;
            recovered += group.recovered;
        }
        out.println("EVENTOS;hbPerdidos=" + lost + ";recuperacoes=" + recovered);
    }

    private Heartbeat latestPublished(int partition, long at, long inc) {
        Heartbeat last = null;
        for (Heartbeat row : heartbeats.get(partition)) {
            if (row.time <= at && (inc < 0 || row.inc == inc) && (last == null || row.time > last.time)) last = row;
        }
        return last;
    }

    private void printDetection() {
        Stats times = new Stats();
        for (int i = 0; i < failures.size(); i++) {
            Failure failure = failures.get(i);
            long nextFailure = Long.MAX_VALUE;
            for (int j = i + 1; j < failures.size(); j++) {
                if (failures.get(j).partition == failure.partition) { nextFailure = failures.get(j).time; break; }
            }
            Heartbeat last = latestPublished(failure.partition, failure.time, -1);
            for (Group group : groups.values()) {
                if (group.partition != failure.partition) continue;
                Suspicion detected = null;
                for (Suspicion suspicion : group.suspicions) {
                    if (suspicion.inc >= 0 && !suspicion.falseSuspicion && suspicion.time >= failure.time
                            && suspicion.time < nextFailure && (last == null || last.inc == suspicion.inc)) {
                        detected = suspicion;
                        break;
                    }
                }
                String prefix = "DETECAO;participante=" + group.participant + ";p=" + failure.partition
                        + ";falha=" + failure.time;
                if (detected == null) out.println(prefix + ";tempoMs=NA;motivo=sem_suspeita_real_comparavel");
                else {
                    long elapsed = detected.time - failure.time;
                    times.add(elapsed);
                    out.println(prefix + ";suspeita=" + detected.time + ";tempoMs=" + elapsed
                            + ";tempoS=" + number(elapsed / 1000.0));
                }
            }
        }
        out.println("DETECAO_RESUMO;amostras=" + times.count + ";minMs=" + times.minimum()
                + ";mediaMs=" + times.mean() + ";maxMs=" + times.maximum());
    }

    private void printLatency() {
        Map<Integer, Stats> totals = new TreeMap<>();
        Map<Integer, Set<Long>> distinct = new TreeMap<>();
        for (int k = 0; k < n; k++) { totals.put(k, new Stats()); distinct.put(k, new HashSet<>()); }
        long negative = 0;
        for (Group group : groups.values()) {
            Stats times = new Stats();
            Set<Long> sampled = new HashSet<>();
            for (Reception received : group.receptions) {
                if (received.count == 0) continue;
                Reception contents = received;
                if (received.headerOnly) {
                    Reception full = receptions.get(received.key());
                    if (full != null && !full.headerOnly) {
                        if (full.count != received.count) {
                            warnings.add("Contagens RECEBIDO incompatíveis: " + received.key());
                            continue;
                        }
                        contents = full;
                    }
                }
                if (!contents.validSpan()) {
                    warnings.add("Intervalo seq não identifica os registos: " + received.key());
                    continue;
                }
                NavigableMap<Long, RecordInfo> batch = records.get(group.partition)
                        .subMap(contents.first, true, contents.last, true);
                if (batch.size() != received.count) warnings.add("Faltam registos do líder para latência: " + received.key());
                for (Map.Entry<Long, RecordInfo> entry : batch.entrySet()) {
                    if (!sampled.add(entry.getKey())) continue;
                    long latency = received.time - entry.getValue().time;
                    times.add(latency);
                    totals.get(group.partition).add(latency);
                    distinct.get(group.partition).add(entry.getKey());
                    out.println("LATENCIA_REGISTO;participante=" + group.participant + ";p=" + group.partition
                            + ";seq=" + entry.getKey() + ";inc=" + received.inc + ";hb=" + received.hb
                            + ";recebido=" + received.time + ";instante=" + entry.getValue().time
                            + ";latenciaMs=" + latency);
                    if (latency < 0) negative++;
                }
            }
            out.println("LATENCIA;participante=" + group.participant + ";p=" + group.partition
                    + ";amostras=" + times.count + ";mediaMs=" + times.mean() + ";maxMs=" + times.maximum());
        }
        for (int k = 0; k < n; k++) {
            Stats times = totals.get(k);
            out.println("LATENCIA_PARTICAO;p=" + k + ";amostras=" + times.count + ";registosDistintos="
                    + distinct.get(k).size() + ";mediaMs=" + times.mean() + ";maxMs=" + times.maximum()
                    + ";registosSemLote=" + (records.get(k).size() - distinct.get(k).size()));
            for (long seq : records.get(k).keySet()) {
                if (!distinct.get(k).contains(seq)) {
                    out.println("LATENCIA_REGISTO;participante=NA;p=" + k + ";seq=" + seq
                            + ";latenciaMs=NA;motivo=sem_rececao_de_lote");
                }
            }
        }
        if (negative > 0) warnings.add(negative + " latências negativas: verificar relógios físicos/dados");
        out.println("LATENCIA_NOTA: resolução limitada pela precisão do instante guardado no registo; fuso=" + zone);
    }

    private void printBytes() {
        ByteStats all = new ByteStats();
        for (int k = 0; k < n; k++) {
            ByteStats bytes = new ByteStats();
            for (Heartbeat row : heartbeats.get(k)) {
                bytes.allBytes += row.bytes;
                if (row.count == 0) continue;
                bytes.records += row.count;
                bytes.nonemptyBytes += row.bytes;
                long first = row.last - row.count + 1;
                long last = row.last;
                Reception received = receptions.get(row.key());
                if (received != null && !received.headerOnly && received.count == row.count && received.validSpan()) {
                    first = received.first;
                    last = received.last;
                    if (last != row.last) warnings.add("ultimoSeq não coincide com o corpo do lote: " + row.key());
                }
                NavigableMap<Long, RecordInfo> transported = records.get(k).subMap(first, true, last, true);
                if (transported.size() != row.count) { bytes.missingBatches++; continue; }
                long payload = 0;
                for (RecordInfo record : transported.values()) payload += utf8(record.line);
                // LOTE = cabeçalho + n mudanças de linha + linhas de registo (LF no encode).
                long headerBytes = row.bytes - payload - row.count;
                long fixedHeader = utf8("LOTE;;;" + row.inc + ";" + row.hb + ";" + row.last + ";" + row.count);
                long endpointBytes = headerBytes - fixedHeader;
                if (endpointBytes < 2) {
                    warnings.add("Contagem de bytes incompatível com o payload: " + row.key());
                    bytes.missingBatches++;
                    continue;
                }
                // REG;endereço;porto;linha — calcula o equivalente sem adivinhar o endereço.
                bytes.equivalentRegBytes += payload + row.count * (utf8("REG;;;") + endpointBytes);
                bytes.comparedRecords += row.count;
            }
            printByteStats(Integer.toString(k), bytes);
            all.add(bytes);
        }
        printByteStats("TODAS", all);
        out.println("BYTES_NOTA: payload UTF-8; exclui tópicos/HTTP/TCP. LOTE inclui o cabeçalho do seu lote;");
        out.println("loteComVaziosPorRegisto inclui também os heartbeats vazios. REG equivalente calculado para amostras com registos disponíveis.");
    }

    private void printByteStats(String partition, ByteStats bytes) {
        out.println("BYTES;p=" + partition + ";registos=" + bytes.records + ";amostrasREG=" + bytes.comparedRecords
                + ";lotePorRegisto=" + ratio(bytes.nonemptyBytes, bytes.records)
                + ";regEquivalentePorRegisto=" + ratio(bytes.equivalentRegBytes, bytes.comparedRecords)
                + ";loteComVaziosPorRegisto=" + ratio(bytes.allBytes, bytes.records)
                + ";lotesSemComparacao=" + bytes.missingBatches);
    }

    private static int utf8(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    private static String ratio(long total, long count) { return count == 0 ? "NA" : number((double) total / count); }
    private static String key(int k, long inc, long hb) { return k + ":" + inc + ":" + hb; }

    private static final class Stats {
        long count;
        double sum;
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        void add(long value) { count++; sum += value; min = Math.min(min, value); max = Math.max(max, value); }
        String mean() { return count == 0 ? "NA" : number(sum / count); }
        String maximum() { return count == 0 ? "NA" : Long.toString(max); }
        String minimum() { return count == 0 ? "NA" : Long.toString(min); }
    }

    private static final class Heartbeat {
        final int partition, count;
        final long inc, hb, time, last, bytes;
        Heartbeat(int partition, long inc, long hb, long time, long last, int count, long bytes) {
            this.partition = partition; this.inc = inc; this.hb = hb; this.time = time;
            this.last = last; this.count = count; this.bytes = bytes;
        }
        String key() { return HeartbeatCheck.key(partition, inc, hb); }
    }

    private static final class Reception {
        final int partition, count;
        final long time, inc, hb, first, last;
        final boolean headerOnly;
        Reception(int partition, long time, Map<String, String> fields) {
            this.partition = partition; this.time = time;
            inc = nonnegative(required(fields, "inc"));
            hb = nonnegative(required(fields, "hb"));
            long lastSeq = nonnegative(required(fields, "ultimoSeq"));
            count = Integer.parseInt(required(fields, "n"));
            if (hb < 1 || count < 0 || count > lastSeq) throw new IllegalArgumentException("RECEBIDO inválido");
            String range = required(fields, "seq");
            if (count == 0) {
                if (!range.equals("vazio")) throw new IllegalArgumentException("Intervalo não vazio para n=0");
                first = 0; last = 0;
            } else {
                String[] endpoints = range.split("\\.\\.", -1);
                if (endpoints.length != 2) throw new IllegalArgumentException("Intervalo seq mal formado");
                first = nonnegative(endpoints[0]); last = nonnegative(endpoints[1]);
                if (first < 1 || last < first) throw new IllegalArgumentException("Intervalo seq inválido");
            }
            headerOnly = Boolean.parseBoolean(fields.getOrDefault("cabecalho", "false"));
        }
        boolean validSpan() { return count > 0 && last - first + 1 == count; }
        String key() { return HeartbeatCheck.key(partition, inc, hb); }
    }

    private static final class RecordInfo {
        final long time;
        final String line;
        RecordInfo(long time, String line) { this.time = time; this.line = line; }
    }

    private static final class Event {
        final long time;
        final String type;
        final Map<String, String> fields;
        Event(long time, String type, Map<String, String> fields) {
            this.time = time; this.type = type; this.fields = fields;
        }
    }

    private static final class Group {
        final String participant;
        final int partition;
        final List<Event> events = new ArrayList<>();
        final List<Reception> receptions = new ArrayList<>();
        final List<Suspicion> suspicions = new ArrayList<>();
        long lost, recovered;
        Group(String participant, int partition) { this.participant = participant; this.partition = partition; }
    }

    private static final class Suspicion {
        final long time, inc;
        final Long observedSilence;
        boolean falseSuspicion;
        Suspicion(long time, long inc, Long observedSilence) {
            this.time = time; this.inc = inc; this.observedSilence = observedSilence;
        }
        String classification() { return inc < 0 ? "INDETERMINADA" : falseSuspicion ? "FALSA_SUSPEITA" : "FALHA_REAL"; }
    }

    private static final class Failure {
        final int partition;
        final long time;
        Failure(int partition, long time) { this.partition = partition; this.time = time; }
    }

    private static final class ByteStats {
        long allBytes, nonemptyBytes, records, equivalentRegBytes, comparedRecords, missingBatches;
        void add(ByteStats other) {
            allBytes += other.allBytes; nonemptyBytes += other.nonemptyBytes; records += other.records;
            equivalentRegBytes += other.equivalentRegBytes; comparedRecords += other.comparedRecords;
            missingBatches += other.missingBatches;
        }
    }
}
