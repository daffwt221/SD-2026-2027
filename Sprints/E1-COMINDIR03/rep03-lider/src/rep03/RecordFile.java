package rep03;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class RecordFile {
    private final Path path;

    public RecordFile(String fileName) {
        this.path = Paths.get(fileName);
    }

    // Intervalo inclusivo, pela ordem do ficheiro; lê todo o histórico.
    public synchronized List<SensorRecord> range(long from, long to) throws IOException {
        List<SensorRecord> out = new ArrayList<>();
        if (!Files.exists(path)) {
            return out;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            SensorRecord r = SensorRecord.fromLine(line);
            if (r.getSeq() >= from && r.getSeq() <= to) {
                out.add(r);
            }
        }
        return out;
    }

    // acrescenta uma linha ao fim do ficheiro (cria-o se não existir)
    public synchronized void append(SensorRecord r) throws IOException {
        Files.writeString(path, r.toLine() + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // número de sequência do último registo guardado (0 se o ficheiro não existir)
    public synchronized long lastSeq() throws IOException {
        SensorRecord last = last();
        return last == null ? 0 : last.getSeq();
    }

    // Último registo persistido, incluindo os relógios; null se não houver histórico.
    public synchronized SensorRecord last() throws IOException {
        if (!Files.exists(path)) {
            return null;
        }
        SensorRecord last = null;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                last = SensorRecord.fromLine(line);
            }
        }
        return last;
    }
}
