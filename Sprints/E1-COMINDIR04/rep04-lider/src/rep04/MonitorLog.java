package rep04;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;

/** Regista eventos das várias threads com um instante físico local e uma linha por evento. */
public final class MonitorLog implements Consumer<String> {
    private final Path path;

    public MonitorLog(String fileName) {
        path = Path.of(fileName);
    }

    @Override
    public synchronized void accept(String event) {
        String line = System.currentTimeMillis() + ";" + event;
        System.out.println(line);
        try {
            Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("REGISTO FALHOU: " + path + " " + e.getMessage());
        }
    }
}
