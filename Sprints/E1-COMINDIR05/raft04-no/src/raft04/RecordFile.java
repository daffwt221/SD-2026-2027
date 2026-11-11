package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Persistência dos registos; o monitor deste objeto nunca chama o monitor do nó. */
public final class RecordFile {
    private final Path path;

    public RecordFile(String fileName) { path = Path.of(fileName); }

    public synchronized void ensureExists() throws IOException {
        Files.writeString(path, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized List<SensorRecord> range(long from, long to) throws IOException {
        List<SensorRecord> result = new ArrayList<>();
        if (!Files.exists(path)) return result;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            SensorRecord record = SensorRecord.fromLine(line);
            if (record.getSeq() >= from && record.getSeq() <= to) result.add(record);
        }
        return result;
    }

    public synchronized void append(SensorRecord record) throws IOException {
        Files.writeString(path, record.toLine() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public synchronized SensorRecord last() throws IOException {
        List<SensorRecord> records = range(1, Long.MAX_VALUE);
        return records.isEmpty() ? null : records.get(records.size() - 1);
    }

    public synchronized long lastSeq() throws IOException {
        SensorRecord record = last();
        return record == null ? 0 : record.getSeq();
    }

    /** Substitui um histórico completo sem expor uma truncagem parcialmente escrita. */
    public synchronized void rewrite(List<SensorRecord> records) throws IOException {
        StringBuilder text = new StringBuilder();
        for (SensorRecord record : records) text.append(record.toLine()).append('\n');
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
