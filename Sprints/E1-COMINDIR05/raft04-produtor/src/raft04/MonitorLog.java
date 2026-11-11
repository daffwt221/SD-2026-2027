package raft04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.function.Consumer;

/** Um único monitor preserva a ordem das linhas de todas as threads do processo. */
public final class MonitorLog implements Consumer<String> {
    private final Path path;
    public MonitorLog(String fileName) { path = Path.of(fileName); }
    @Override public void accept(String event) { acceptAt(System.currentTimeMillis(), event); }

    public synchronized void acceptAt(long instant, String event) {
        String line = instant + ";" + event.replace('\n', ' ').replace('\r', ' ');
        System.out.println(line);
        try {
            Files.writeString(path, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("REGISTO FALHOU: " + path + " " + e.getMessage());
        }
    }
}
