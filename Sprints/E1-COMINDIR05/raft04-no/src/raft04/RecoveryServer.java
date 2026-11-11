package raft04;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Qualquer nó serve um snapshot do seu ficheiro; a recuperação não adquire o monitor do nó. */
final class RecoveryServer implements AutoCloseable {
    private final ServerSocket server;
    private final RecordFile file;
    private final int partition;
    private final BooleanSupplier isolated;
    private final Consumer<String> log;

    RecoveryServer(int partition, int port, RecordFile file,
                   BooleanSupplier isolated, Consumer<String> log) throws IOException {
        this.partition = partition;
        this.file = file;
        this.isolated = isolated;
        this.log = log;
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port));
    }

    void run() {
        while (!server.isClosed()) {
            try (Socket socket = server.accept()) {
                handle(socket);
            } catch (IOException | IllegalArgumentException e) {
                if (!server.isClosed()) log.accept((isolated.getAsBoolean() ? "IGNORADO" : "ERRO")
                        + ";p" + partition + ";origem=TCP;detalhe=" + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        if (isolated.getAsBoolean()) throw new IOException("nó isolado");
        socket.setSoTimeout(Wire.T_TCP_MS);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        String line = in.readLine();
        if (line == null) throw new EOFException("pedido ausente");
        String[] fields = line.trim().split("\\s+");
        if (fields.length != 3 || !fields[0].equals("PEDIDO")) throw new IOException("PEDIDO <de> <até> esperado");
        long from = Long.parseLong(fields[1]);
        long to = Long.parseLong(fields[2]);
        if (from < 1 || to < from) throw new IOException("intervalo inválido");
        List<SensorRecord> records = file.range(from, to);
        for (SensorRecord record : records) {
            if (isolated.getAsBoolean()) throw new IOException("isolamento durante recuperação");
            out.write(record.toLine());
            out.newLine();
        }
        if (isolated.getAsBoolean()) throw new IOException("nó isolado");
        out.write("FIM\n");
        out.flush();
        log.accept("RECEBIDO;p" + partition + ";origem=PEDIDO_TCP;de=" + from
                + ";ate=" + to + ";n=" + records.size());
    }

    @Override public void close() throws IOException { server.close(); }
}
