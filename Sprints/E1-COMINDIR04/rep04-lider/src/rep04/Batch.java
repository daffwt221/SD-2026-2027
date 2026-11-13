package rep04;

import java.util.ArrayList;
import java.util.List;

/** Um heartbeat e os registos acumulados desde o lote anterior. */
public final class Batch {
    private final String address;
    private final int port;
    private final long inc;
    private final long hb;
    private final long lastSeq;
    private final List<SensorRecord> records;

    public Batch(String address, int port, long inc, long hb, long lastSeq,
                 List<SensorRecord> records) {
        if (address == null || address.isBlank() || address.indexOf(';') >= 0
                || address.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new IllegalArgumentException("Endereço inválido");
        }
        if (port < 1 || port > 65535 || inc < 0 || hb < 1 || lastSeq < 0) {
            throw new IllegalArgumentException("Cabeçalho LOTE inválido");
        }
        this.address = address;
        this.port = port;
        this.inc = inc;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.records = List.copyOf(records);
        long previous = 0;
        for (SensorRecord record : this.records) {
            if (record.getSeq() <= previous || record.getSeq() > lastSeq) {
                throw new IllegalArgumentException("Sequências do lote fora de ordem/ultimoSeq");
            }
            previous = record.getSeq();
        }
    }

    public String encode() {
        StringBuilder text = new StringBuilder("LOTE;")
                .append(address).append(';').append(port).append(';')
                .append(inc).append(';').append(hb).append(';')
                .append(lastSeq).append(';').append(records.size());
        for (SensorRecord record : records) text.append('\n').append(record.toLine());
        return text.toString();
    }

    /** n é a dimensão dos vetores (N partições); a contagem do cabeçalho é independente. */
    public static Batch decode(String msg, int n) {
        if (msg == null || n < 1) throw new IllegalArgumentException("LOTE ou N inválido");
        Header header = decodeHeader(msg);
        String[] lines = msg.split("\\R", -1);
        int length = lines.length;
        // Aceita uma única mudança de linha final, mas não linhas de registo vazias.
        if (length > 1 && lines[length - 1].isEmpty()) length--;
        int count = header.getCount();
        if (count < 0 || length - 1 != count) {
            throw new IllegalArgumentException("Número de registos diferente do n do cabeçalho");
        }
        List<SensorRecord> records = new ArrayList<>();
        for (int i = 1; i < length; i++) {
            SensorRecord record = SensorRecord.fromLine(lines[i]);
            if (record.getVector().size() != n) {
                throw new IllegalArgumentException("Dimensão vetorial diferente de N");
            }
            records.add(record);
        }
        return new Batch(header.getAddress(), header.getPort(), header.getInc(), header.getHb(),
                header.getLastSeq(), records);
    }

    /** Não interpreta nem valida as linhas do corpo; usado pelo produtor. */
    public static Header decodeHeader(String msg) {
        if (msg == null) throw new IllegalArgumentException("LOTE ausente");
        String first = msg.lines().findFirst().orElse("");
        String[] fields = first.split(";", -1);
        if (fields.length != 7 || !fields[0].equals("LOTE")) {
            throw new IllegalArgumentException("Cabeçalho LOTE mal formado");
        }
        return new Header(fields[1], Integer.parseInt(fields[2]), Long.parseLong(fields[3]),
                Long.parseLong(fields[4]), Long.parseLong(fields[5]), Integer.parseInt(fields[6]));
    }

    public static final class Header {
        private final Batch metadata;
        private final int count;

        private Header(String address, int port, long inc, long hb, long lastSeq, int count) {
            metadata = new Batch(address, port, inc, hb, lastSeq, List.of());
            if (count < 0 || count > lastSeq) throw new IllegalArgumentException("Contagem LOTE inválida");
            this.count = count;
        }

        public String getAddress() { return metadata.getAddress(); }
        public int getPort() { return metadata.getPort(); }
        public long getInc() { return metadata.getInc(); }
        public long getHb() { return metadata.getHb(); }
        public long getLastSeq() { return metadata.getLastSeq(); }
        public int getCount() { return count; }

        public String receivedEvent(int partition) {
            String range = count == 0 ? "vazio" : (getLastSeq() - count + 1) + ".." + getLastSeq();
            return "RECEBIDO;p" + partition + ";inc=" + getInc() + ";hb=" + getHb()
                    + ";ultimoSeq=" + getLastSeq() + ";n=" + count + ";seq=" + range + ";cabecalho=true";
        }
    }

    public String getAddress() { return address; }
    public int getPort() { return port; }
    public long getInc() { return inc; }
    public long getHb() { return hb; }
    public long getLastSeq() { return lastSeq; }
    public List<SensorRecord> getRecords() { return records; }

    public String receivedEvent(int partition) {
        String range = records.isEmpty() ? "vazio"
                : records.get(0).getSeq() + ".." + records.get(records.size() - 1).getSeq();
        return "RECEBIDO;p" + partition + ";inc=" + inc + ";hb=" + hb
                + ";ultimoSeq=" + lastSeq + ";n=" + records.size() + ";seq=" + range;
    }
}
