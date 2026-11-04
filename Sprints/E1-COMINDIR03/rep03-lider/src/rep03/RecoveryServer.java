package rep03;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.LongSupplier;

/** Atende recuperações em série na thread que chama run(), usando o histórico do líder. */
final class RecoveryServer implements AutoCloseable {
    private final ServerSocket server;
    private final RecordFile file;
    private final LongSupplier lastSupplier;

    RecoveryServer(int partition, int port, String host, RecordFile file, LongSupplier lastSupplier)
            throws IOException {
        this.file = file;
        this.lastSupplier = lastSupplier;
        this.server = new ServerSocket(port);
        synchronized (file) {
            System.out.println("Líder p" + partition + ": recuperação TCP no porto " + port
                    + ", endereço anunciado " + host + ", último=" + lastSupplier.getAsLong());
        }
    }

    void run() {
        while (true) {
            // accept bloqueia; a thread chamadora atende uma recuperação de cada vez.
            try (Socket socket = server.accept()) {
                handleRecovery(socket);
            } catch (IOException | IllegalArgumentException e) {
                System.out.println("Recuperação interrompida: " + e.getClass().getSimpleName()
                        + ": " + e.getMessage());
            }
        }
    }

    private void handleRecovery(Socket socket) throws IOException {
        socket.setSoTimeout(Wire.T_TCP_MS);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

        String request = in.readLine(); // Espera pelo pedido, no máximo T_TCP_MS.
        long from;
        long to;
        try {
            if (request == null) throw new IllegalArgumentException("pedido ausente");
            String[] fields = request.trim().split("\\s+");
            if (fields.length != 3 || !fields[0].equals("PEDIDO")) {
                throw new IllegalArgumentException("formato esperado: PEDIDO <de> <até>");
            }
            from = Long.parseLong(fields[1]);
            to = Long.parseLong(fields[2]);
            if (from < 1 || to < 1 || from > to) {
                throw new IllegalArgumentException("intervalo inválido");
            }
        } catch (IllegalArgumentException e) {
            out.write("ERRO " + e.getMessage());
            out.newLine();
            out.flush();
            return;
        }

        List<SensorRecord> records;
        synchronized (file) {
            to = Math.min(to, lastSupplier.getAsLong());
            records = file.range(from, to);
        }
        // O snapshot já está em memória: uma réplica lenta não prende o monitor do ficheiro.
        for (SensorRecord record : records) {
            out.write(record.toLine());
            out.newLine();
        }
        out.write("FIM");
        out.newLine();
        out.flush();
        System.out.println("RECUPERAÇÃO: " + from + ".." + to + " para "
                + socket.getRemoteSocketAddress() + " (" + records.size() + " registos)");
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
