package rep04;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

/** Ficheiros sintéticos com resultados conhecidos, independentes do agendamento dos processos. */
public final class HeartbeatCheckTest {
    private static int assertions;

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        assertions++;
    }

    private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    private static String row(Batch batch, long time) {
        return batch.getInc() + ";" + batch.getHb() + ";" + time + ";" + batch.getLastSeq()
                + ";" + batch.getRecords().size() + ";" + bytes(batch.encode());
    }

    private static String report(Path folder, Path failures) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            require(HeartbeatCheck.check(1, folder, failures, out) == 0, "Relatorio terminou com sucesso");
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static void write(Path folder, String name, String content) throws Exception {
        Files.writeString(folder.resolve(name), content, StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path folder = root.resolve("controlado");
        Files.createDirectories(folder);
        LocalDateTime start = LocalDateTime.of(2026, 11, 6, 12, 0);
        long base = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        SensorRecord first = new SensorRecord(1, "SÉ1", 21.0, start.plusSeconds(1).toString(), 1,
                new VectorClock(new long[] {1}));
        SensorRecord second = new SensorRecord(2, "S02", 22.0, start.plusSeconds(6).toString(), 2,
                new VectorClock(new long[] {2}));
        SensorRecord recovered = new SensorRecord(3, "S03", 23.0, start.plusSeconds(11).toString(), 3,
                new VectorClock(new long[] {3}));
        String address = "réplica.local";
        Batch empty = new Batch(address, 7100, 1000, 1, 0, List.of());
        Batch one = new Batch(address, 7100, 1000, 2, 1, List.of(first));
        Batch two = new Batch(address, 7100, 1000, 3, 2, List.of(second));
        Batch resumed = new Batch(address, 7100, 1000, 5, 2, List.of());
        Batch restarted = new Batch(address, 7100, 2000, 1, 3, List.of());
        write(folder, "lider-p0.txt", first.toLine() + "\n" + second.toLine() + "\n" + recovered.toLine() + "\n");
        write(folder, "hb-p0.txt", String.join("\n", row(empty, base), row(one, base + 5000),
                row(two, base + 10000), row(resumed, base + 26900), row(restarted, base + 45000)) + "\n");
        write(folder, "monitor-replica1.txt", String.join("\n",
                (base + 5100) + ";" + one.receivedEvent(0),
                (base + 10100) + ";" + two.receivedEvent(0),
                (base + 10200) + ";" + one.receivedEvent(0),
                (base + 26000) + ";SUSPEITA;p0;silencio_ms=15900",
                (base + 27000) + ";HB_PERDIDO;p0;esperado=4;recebido=5",
                (base + 27000) + ";RETOMA;p0;inc=1000;hb=5",
                (base + 27000) + ";" + resumed.receivedEvent(0),
                (base + 43000) + ";SUSPEITA;p0;silencio_ms=16000",
                (base + 45100) + ";REINICIO;p0;inc=1000->2000",
                (base + 45100) + ";RETOMA;p0;inc=2000;hb=1",
                (base + 45100) + ";" + restarted.receivedEvent(0),
                (base + 45150) + ";RECUPERACAO;p0;de=3;ate=3",
                (base + 62000) + ";SUSPEITA;p0;silencio_ms=16900") + "\n");
        write(folder, "monitor-produtor.txt", (base + 5200) + ";"
                + Batch.decodeHeader(one.encode()).receivedEvent(0) + "\n" + (base + 10200) + ";"
                + Batch.decodeHeader(two.encode()).receivedEvent(0) + "\n");
        write(folder, "monitor-arranque.txt", (base + 1000) + ";SUSPEITA;p0;silencio_ms=1000\n");
        write(folder, "falhas.txt", "p0;" + (base + 35000) + "\np0;" + (base + 60000) + "\n");

        String report = report(folder, folder.resolve("falhas.txt"));
        write(folder, "HeartbeatCheck.log", report);
        require(report.contains("LOTES;p=0;inc=1000;lotes=4;vazios=2;proporcaoVazios=0.500;registosMedia=0.500;registosMax=1;intervaloMediaMs=8966.667;intervaloMaxMs=16900"),
                "Estatisticas por incarnacao e intervalos exatos");
        require(report.contains("LOTES;p=0;inc=2000;lotes=1;vazios=1;proporcaoVazios=1.000;registosMedia=0.000;registosMax=0;intervaloMediaMs=NA;intervaloMaxMs=NA"),
                "Nao mistura intervalos de incarnacoes diferentes");
        require(report.contains("MONITOR;participante=replica1;p=0;reais=2;falsas=1;indeterminadas=0;hbPerdidos=1;recuperacoes=1"),
                "Retoma da mesma incarnacao e reinicio sao classificados de forma diferente");
        require(report.contains("classe=FALSA_SUSPEITA;silencioPublicadoMs=16000"), "Silencio medido desde o ultimo publicado");
        require(report.contains("classe=INDETERMINADA;silencioPublicadoMs=NA"), "Sem primeiro heartbeat nao inventa incarnacao");
        require(report.contains("DETECAO_RESUMO;amostras=2;minMs=2000;mediaMs=5000.000;maxMs=8000"),
                "Cada falha usa apenas a suspeita real da sua incarnacao");
        require(report.contains("LATENCIA;participante=replica1;p=0;amostras=2;mediaMs=4100.000;maxMs=4100"),
                "Nao conta duplicados nem registos recuperados como entregas de lote");
        require(report.contains("LATENCIA;participante=produtor;p=0;amostras=2;mediaMs=4200.000;maxMs=4200"),
                "Latencias do observador de cabecalhos");
        require(report.contains("LATENCIA_PARTICAO;p=0;amostras=4;registosDistintos=2;mediaMs=4150.000;maxMs=4200"),
                "Agregacao explicita por particao");
        require(report.contains("LATENCIA_REGISTO;participante=replica1;p=0;seq=1;inc=1000;hb=2;"),
                "Relatorio inclui a latencia de cada registo recebido");
        require(report.contains("LATENCIA_REGISTO;participante=NA;p=0;seq=3;latenciaMs=NA;motivo=sem_rececao_de_lote"),
                "Registos recuperados sem lote ficam sem latencia inventada");
        double batchBytes = (bytes(one.encode()) + bytes(two.encode())) / 2.0;
        double regBytes = (bytes("REG;" + address + ";7100;" + first.toLine())
                + bytes("REG;" + address + ";7100;" + second.toLine())) / 2.0;
        double withEmpty = (bytes(empty.encode()) + bytes(one.encode()) + bytes(two.encode())
                + bytes(resumed.encode()) + bytes(restarted.encode())) / 2.0;
        require(report.contains("BYTES;p=0;registos=2;amostrasREG=2;lotePorRegisto=" + number(batchBytes)
                + ";regEquivalentePorRegisto=" + number(regBytes) + ";loteComVaziosPorRegisto=" + number(withEmpty)
                + ";lotesSemComparacao=0"), "Comparacao exata de payload UTF-8, com cabecalhos unicode e lotes vazios");
        require(!report(folder, null).contains("DETECAO;participante="), "Sem anotacoes nao calcula tempos de detecao");

        Path missing = root.resolve("sem-registos");
        Files.createDirectories(missing);
        write(missing, "hb-p0.txt", row(one, base + 5000) + "\n");
        String incomplete = report(missing, null);
        require(incomplete.contains("regEquivalentePorRegisto=NA"), "Sem registos nao inventa bytes REG");
        require(incomplete.contains("lotesSemComparacao=1"), "Cobertura incompleta fica explicita");

        Path inconsistent = root.resolve("ultimoSeq-inconsistente");
        Files.createDirectories(inconsistent);
        Batch wrongLast = new Batch(address, 7100, 3000, 1, 3, List.of(first, second));
        write(inconsistent, "lider-p0.txt", first.toLine() + "\n" + second.toLine() + "\n" + recovered.toLine() + "\n");
        write(inconsistent, "hb-p0.txt", row(wrongLast, base + 15000) + "\n");
        write(inconsistent, "monitor-replica1.txt", (base + 15100) + ";" + wrongLast.receivedEvent(0) + "\n");
        write(inconsistent, "monitor-produtor.txt", (base + 15200) + ";"
                + Batch.decodeHeader(wrongLast.encode()).receivedEvent(0) + "\n");
        String metadataError = report(inconsistent, null);
        require(metadataError.contains("ultimoSeq não coincide com o corpo do lote"), "Deteta ultimoSeq incoerente");
        require(metadataError.contains("LATENCIA;participante=produtor;p=0;amostras=2;mediaMs=11700.000;maxMs=14200"),
                "Correlaciona o cabecalho do produtor com o corpo observado sem inventar registos transportados");

        Path invalid = root.resolve("invalido");
        Files.createDirectories(invalid);
        write(invalid, "hb-p0.txt", "1000;1;5000;0;0\n");
        boolean rejected = false;
        try { report(invalid, null); }
        catch (IllegalArgumentException e) { rejected = e.getMessage().contains("hb-p0.txt:1:"); }
        require(rejected, "Dados malformados indicam ficheiro e linha");
        Path invalidMonitor = root.resolve("monitor-invalido");
        Files.createDirectories(invalidMonitor);
        write(invalidMonitor, "monitor-produtor.txt", base + ";RECEBIDO;p0;hb=1;ultimoSeq=0;n=0;seq=vazio\n");
        boolean invalidEvent = false;
        try { report(invalidMonitor, null); }
        catch (IllegalArgumentException e) { invalidEvent = e.getMessage().contains("monitor-produtor.txt:1:"); }
        require(invalidEvent, "Eventos malformados indicam ficheiro e linha");
        System.out.println("PASS: " + assertions + " verificacoes de metricas controladas. Artefactos: " + root);
    }
}
