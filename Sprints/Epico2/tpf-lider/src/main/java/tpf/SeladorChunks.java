package tpf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Selagem local dos registos duráveis. Não representa quorum nem replicação.
 * .hdr é publicado por último; na reinicialização apenas esses metadados
 * completos representam chunks confirmados como selados.
 */
public final class SeladorChunks implements AutoCloseable {
    public static final int LIMITE_BYTES = 65_536;
    public static final Duration LIMITE_TEMPO = Duration.ofSeconds(5);
    private final Path log, pasta, chave;
    private final ChunkCrypto crypto;
    private final ScheduledExecutorService executor;
    private long ultimoSelado;
    private long pendenteDesdeNanos = -1;

    public SeladorChunks(Path log, Path pasta, Path chave) throws IOException, GeneralSecurityException {
        this.log = log.toAbsolutePath().normalize();
        this.pasta = pasta.toAbsolutePath().normalize();
        this.chave = chave.toAbsolutePath().normalize();
        Files.createDirectories(this.pasta);
        boolean haChunks = false;
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(this.pasta, "*.{hdr,bin}")) {
            haChunks = dir.iterator().hasNext();
        }
        if (haChunks && !Files.exists(this.chave))
            throw new IOException("Existem chunks selados mas a master.key desapareceu; não criar outra chave!");
        criarChaveSeFaltar();
        this.crypto = ChunkCrypto.fromFile(this.chave);
        this.ultimoSelado = conferirExistentes();
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "selador-chunks");
            t.setDaemon(true);
            return t;
        });
    }

    /** Cria a chave uma vez e sincroniza-a antes de a utilizar. */
    private void criarChaveSeFaltar() throws IOException {
        if (Files.exists(chave)) return;
        if (chave.getParent() != null) Files.createDirectories(chave.getParent());
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        try (FileChannel fc = FileChannel.open(chave, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            escreverTudo(fc, bytes);
            fc.force(true);
        } catch (FileAlreadyExistsException ignored) {
            // Outro processo criou a chave; não a substituir.
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private long conferirExistentes() throws IOException, GeneralSecurityException {
        TreeMap<Long, Long> intervalos = new TreeMap<>();
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(pasta, "*.hdr")) {
            for (Path hdr : dir) {
                Properties p = new Properties();
                try (var in = Files.newInputStream(hdr)) { p.load(in); }
                String id = hdr.getFileName().toString().replaceFirst("\\.hdr$", "");
                long de = Long.parseLong(p.getProperty("lsnDe"));
                long ate = Long.parseLong(p.getProperty("lsnAte"));
                int versao = Integer.parseInt(p.getProperty("keyVersion"));
                byte[] salt = HexFormat.of().parseHex(p.getProperty("salt"));
                byte[] nonce = HexFormat.of().parseHex(p.getProperty("nonce"));
                Path bin = pasta.resolve(id + ".bin");
                byte[] plain = crypto.open(new Sealed(id, versao, salt, nonce, Files.readAllBytes(bin)));
                if (plain.length != Integer.parseInt(p.getProperty("tamanhoPlaintext")) || ate < de)
                    throw new IOException("Metadados inválidos: " + hdr);
                if (intervalos.putIfAbsent(de, ate) != null)
                    throw new IOException("Intervalos duplicados de chunks selados");
            }
        }
        long ultimo = 0;
        for (var e : intervalos.entrySet()) {
            if (e.getKey() != ultimo + 1) throw new IOException("Lacuna/sobreposição nos chunks selados");
            ultimo = e.getValue();
        }
        return ultimo;
    }

    public void iniciar() {
        executor.scheduleWithFixedDelay(() -> {
            try { verificarESelar(false); }
            catch (Exception e) { System.err.println("ERRO selador: " + e); }
        }, 0, 250, TimeUnit.MILLISECONDS);
    }

    /** Para testes e encerramento controlado: força selagem do que está pendente. */
    public synchronized int selarPendentesAgora() throws IOException, GeneralSecurityException {
        return verificarESelar(true);
    }

    /** Devolve a quantidade de chunks criados nesta chamada. */
    private synchronized int verificarESelar(boolean forcar) throws IOException, GeneralSecurityException {
        if (!Files.exists(log)) return 0;
        byte[] raw = Files.readAllBytes(log);
        if (raw.length > 0 && raw[raw.length - 1] != '\n')
            throw new IOException("Registo incompleto no log; selagem interrompida");
        String texto = new String(raw, StandardCharsets.UTF_8);
        String[] linhas = texto.split("\n", -1);
        long esperado = 1;
        List<String> pendentes = new ArrayList<>();
        for (int i = 0; i < linhas.length - 1; i++) {
            String linha = linhas[i];
            String[] campos = linha.split(";", 2);
            if (campos.length != 2 || Long.parseLong(campos[0]) != esperado)
                throw new IOException("LSN fora de sequência no log");
            if (esperado > ultimoSelado) pendentes.add(linha + "\n");
            esperado++;
        }
        if (ultimoSelado > esperado - 1)
            throw new IOException("O log está atrás dos chunks selados; recuperação necessária");
        if (pendentes.isEmpty()) { pendenteDesdeNanos = -1; return 0; }
        if (pendenteDesdeNanos < 0) pendenteDesdeNanos = System.nanoTime();
        long decorrido = System.nanoTime() - pendenteDesdeNanos;
        int totalBytes = pendentes.stream().mapToInt(s -> s.getBytes(StandardCharsets.UTF_8).length).sum();
        if (!forcar && totalBytes < LIMITE_BYTES && decorrido < LIMITE_TEMPO.toNanos()) return 0;

        int criados = 0;
        int indice = 0;
        while (indice < pendentes.size()) {
            long de = ultimoSelado + 1;
            int tamanho = 0, quantidade = 0;
            while (indice + quantidade < pendentes.size()) {
                int tamanhoLinha = pendentes.get(indice + quantidade).getBytes(StandardCharsets.UTF_8).length;
                if (tamanhoLinha > LIMITE_BYTES)
                    throw new IOException("Um registo excede o tamanho máximo de chunk");
                if (tamanho + tamanhoLinha > LIMITE_BYTES) break;
                tamanho += tamanhoLinha;
                quantidade++;
            }
            // Sem prazo nem limite de tamanho: mantemos o último chunk parcial pendente.
            if (!forcar && indice + quantidade == pendentes.size()
                    && tamanho < LIMITE_BYTES && decorrido < LIMITE_TEMPO.toNanos()) break;
            StringBuilder sb = new StringBuilder(tamanho);
            for (int n = 0; n < quantidade; n++) sb.append(pendentes.get(indice + n));
            byte[] original = sb.toString().getBytes(StandardCharsets.UTF_8);
            long ate = de + quantidade - 1;
            gravarChunk(de, ate, original);
            ultimoSelado = ate;
            indice += quantidade;
            criados++;
            System.out.printf("Chunk selado: LSN %d-%d (%d bytes originais)%n", de, ate, original.length);
        }
        pendenteDesdeNanos = indice == pendentes.size() ? -1 : System.nanoTime();
        return criados;
    }

    private void gravarChunk(long de, long ate, byte[] original) throws IOException, GeneralSecurityException {
        Sealed sealed = crypto.seal(original);
        String id = sealed.chunkId();
        Path bin = pasta.resolve(id + ".bin");
        Path hdr = pasta.resolve(id + ".hdr");
        Path binTmp = Files.createTempFile(pasta, ".chunk-", ".tmp");
        Path hdrTmp = Files.createTempFile(pasta, ".header-", ".tmp");
        try {
            try (FileChannel fc = FileChannel.open(binTmp, StandardOpenOption.WRITE)) {
                escreverTudo(fc, sealed.ciphertext()); fc.force(true);
            }
            Properties p = new Properties();
            p.setProperty("lsnDe", Long.toString(de));
            p.setProperty("lsnAte", Long.toString(ate));
            p.setProperty("keyVersion", Integer.toString(sealed.keyVersion()));
            p.setProperty("salt", HexFormat.of().formatHex(sealed.salt()));
            p.setProperty("nonce", HexFormat.of().formatHex(sealed.nonce()));
            p.setProperty("tamanhoPlaintext", Integer.toString(original.length));
            try (var out = Files.newOutputStream(hdrTmp)) { p.store(out, "TPF chunk metadata"); }
            try (FileChannel fc = FileChannel.open(hdrTmp, StandardOpenOption.WRITE)) { fc.force(true); }
            Files.move(binTmp, bin, StandardCopyOption.ATOMIC_MOVE);
            Files.move(hdrTmp, hdr, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(binTmp); Files.deleteIfExists(hdrTmp);
        }
    }

    private static void escreverTudo(FileChannel fc, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) fc.write(buffer);
    }

    public synchronized long ultimoLsnSelado() { return ultimoSelado; }

    @Override public void close() { executor.shutdownNow(); }
}
