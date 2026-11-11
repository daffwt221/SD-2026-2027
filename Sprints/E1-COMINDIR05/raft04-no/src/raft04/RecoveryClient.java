package raft04;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Só entrega ao chamador a resposta depois de receber FIM; não aplica respostas parciais. */
final class RecoveryClient {
    private final int partition;
    private final int self;
    private final Partitioner partitioner;

    RecoveryClient(int partition, int self, int n) {
        this.partition = partition;
        this.self = self;
        partitioner = new Partitioner(n);
    }

    List<SensorRecord> fetch(String host, int port, long from, long to, long batchTerm) throws IOException {
        if (Wire.isolated(partition, self)) throw new IOException("cliente isolado");
        List<SensorRecord> result = new ArrayList<>();
        if (to < from) return result;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Wire.T_TCP_MS);
            socket.setSoTimeout(Wire.T_TCP_MS);
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out.write("PEDIDO " + from + " " + to + "\n");
            out.flush();
            long expected = from;
            while (true) {
                if (Wire.isolated(partition, self)) throw new IOException("isolamento durante recuperação");
                String line = in.readLine();
                if (line == null) throw new EOFException("resposta sem FIM");
                if (line.equals("FIM")) break;
                if (line.startsWith("ERRO")) throw new IOException(line);
                SensorRecord record;
                try { record = SensorRecord.fromLine(line); }
                catch (IllegalArgumentException e) { throw new IOException("registo TCP inválido", e); }
                if (record.getSeq() != expected || record.getSeq() > to || record.getTerm() > batchTerm
                        || record.getVector().size() != partitioner.count()
                        || record.getVector().get(partition) != record.getSeq()
                        || partitioner.of(record.getSensorId()) != partition) {
                    throw new IOException("resposta TCP fora da ordem, termo ou partição esperados");
                }
                result.add(record);
                expected++;
            }
        }
        if (Wire.isolated(partition, self)) throw new IOException("cliente isolado");
        return result;
    }
}
