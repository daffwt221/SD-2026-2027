package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Log local append-only. O ACK devolvido representa apenas persistência local,
 * nunca quorum de réplicas.
 */
public final class RegistoLocal {
    private final Path ficheiro;
    private long ultimoLsn;
    private final EventosLider eventos;

    public RegistoLocal(Path ficheiro) throws IOException {
        this(ficheiro, ficheiro.toAbsolutePath().normalize().resolveSibling("eventos-lider.txt"));
    }

    public RegistoLocal(Path ficheiro, Path eventosPath) throws IOException {
        this.eventos = new EventosLider(eventosPath);
        this.ficheiro = ficheiro.toAbsolutePath().normalize();
        Path pasta = this.ficheiro.getParent();
        if (pasta != null) Files.createDirectories(pasta);
        if (Files.notExists(this.ficheiro)) Files.createFile(this.ficheiro);

        byte[] bytes = Files.readAllBytes(this.ficheiro);
        if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
            throw new IOException("registos.log tem uma linha incompleta; recuperação manual necessária.");
        }
        List<String> linhas = Files.readAllLines(this.ficheiro, StandardCharsets.UTF_8);
        long anterior = 0;
        for (String linha : linhas) {
            String[] campos = linha.split(";", 5);
            if (campos.length != 5) {
                throw new IOException("Formato inválido no log; não é seguro continuar.");
            }
            try {
                long atual = Long.parseLong(campos[0]);
                if (atual != anterior + 1) throw new NumberFormatException("LSN fora de sequência");
                Instant.parse(campos[1]);
                Base64.getDecoder().decode(campos[4]);
                anterior = atual;
            } catch (IllegalArgumentException e) {
                throw new IOException("Registo inválido; não é seguro continuar.", e);
            }
        }
        ultimoLsn = anterior;
    }

    /** Atribui LSN e confirma apenas quando FileChannel.force terminar. */
    public synchronized long acrescentar(String autor, String canal, String texto) throws IOException {
        if (autor == null || canal == null || texto == null) {
            throw new IllegalArgumentException("Os campos do registo não podem ser nulos.");
        }
        if (autor.contains(";") || canal.contains(";") || autor.contains("\n") || canal.contains("\n")) {
            throw new IllegalArgumentException("Autor ou canal inválido.");
        }
        long proximo = Math.addExact(ultimoLsn, 1);
        String conteudo = Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
        String linha = proximo + ";" + Instant.now() + ";" + autor + ";" + canal + ";" + conteudo + "\n";
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(linha);
        try (FileChannel canalFicheiro = FileChannel.open(ficheiro, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            while (buffer.hasRemaining()) canalFicheiro.write(buffer);
            canalFicheiro.force(true);
        }
        ultimoLsn = proximo;
        try { eventos.escrever("REGISTO", "lsn=" + proximo + ";autor=" + autor + ";canal=" + canal); }
        catch (IOException e) { System.err.println("REGISTO persistido; falha ao escrever evento: " + e); }
        return proximo;
    }

    public synchronized long ultimoLsn() {
        return ultimoLsn;
    }
}
