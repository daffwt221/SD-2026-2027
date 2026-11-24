package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

/** Eventos informativos locais REGISTO e SELADO, persistidos com force(true). */
public final class EventosLider {
    private final Path path;
    public EventosLider(Path path) { this.path = path.toAbsolutePath().normalize(); }
    public synchronized void escrever(String evento, String detalhe) throws IOException {
        if (!evento.matches("REGISTO|SELADO")) throw new IllegalArgumentException("Evento inválido");
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        String d = detalhe.replace('\n', ' ').replace('\r', ' ');
        ByteBuffer b = StandardCharsets.UTF_8.encode(Instant.now() + ";" + evento + ";" + d + "\n");
        try (FileChannel fc = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            while (b.hasRemaining()) fc.write(b);
            fc.force(true);
        }
    }
}
