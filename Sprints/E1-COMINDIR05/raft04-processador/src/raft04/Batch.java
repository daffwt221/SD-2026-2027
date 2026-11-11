package raft04;

import java.util.ArrayList;
import java.util.List;

/** Um heartbeat e os registos acumulados desde o lote anterior. */
public final class Batch {
    private final long term;
    private final int leader;
    private final String address;
    private final int port;
    private final long hb;
    private final long lastSeq;
    private final long lastTerm;
    private final List<SensorRecord> records;

    public Batch(long term, int leader, String address, int port, long hb, long lastSeq, long lastTerm,
                 List<SensorRecord> records) {
        if (address == null || address.isBlank() || address.indexOf(';') >= 0
                || address.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new IllegalArgumentException("Endereço inválido");
        }
        if (term < 1 || leader < 1 || leader > Wire.GRUPO || port < 1 || port > 65535
                || hb < 1 || lastSeq < 0 || lastTerm < 0 || lastTerm > term
                || ((lastSeq == 0) != (lastTerm == 0))) {
            throw new IllegalArgumentException("Cabeçalho LOTE inválido");
        }
        this.term = term;
        this.leader = leader;
        this.address = address;
        this.port = port;
        this.hb = hb;
        this.lastSeq = lastSeq;
        this.lastTerm = lastTerm;
        this.records = List.copyOf(records);
        long previous = 0;
        for (SensorRecord record : this.records) {
            if (record.getSeq() <= previous || record.getSeq() > lastSeq || record.getTerm() > term) {
                throw new IllegalArgumentException("Sequências do lote fora de ordem/ultimoSeq");
            }
            previous = record.getSeq();
        }
    }

    public String encode() {
        StringBuilder text = new StringBuilder("LOTE;")
                .append(term).append(';').append(leader).append(';')
                .append(address).append(';').append(port).append(';')
                .append(hb).append(';').append(lastSeq).append(';')
                .append(lastTerm).append(';').append(records.size());
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
        return new Batch(header.getTerm(), header.getLeader(), header.getAddress(), header.getPort(),
                header.getHb(), header.getLastSeq(), header.getLastTerm(), records);
    }

    /** Não interpreta nem valida as linhas do corpo; usado pelo produtor. */
    public static Header decodeHeader(String msg) {
        if (msg == null) throw new IllegalArgumentException("LOTE ausente");
        String first = msg.lines().findFirst().orElse("");
        String[] fields = first.split(";", -1);
        if (fields.length != 9 || !fields[0].equals("LOTE")) {
            throw new IllegalArgumentException("Cabeçalho LOTE mal formado");
        }
        return new Header(Long.parseLong(fields[1]), Integer.parseInt(fields[2]), fields[3],
                Integer.parseInt(fields[4]), Long.parseLong(fields[5]), Long.parseLong(fields[6]),
                Long.parseLong(fields[7]), Integer.parseInt(fields[8]));
    }

    public static final class Header {
        private final Batch metadata;
        private final int count;

        private Header(long term, int leader, String address, int port, long hb,
                       long lastSeq, long lastTerm, int count) {
            metadata = new Batch(term, leader, address, port, hb, lastSeq, lastTerm, List.of());
            if (count < 0 || count > lastSeq) throw new IllegalArgumentException("Contagem LOTE inválida");
            this.count = count;
        }

        public String getAddress() { return metadata.getAddress(); }
        public int getPort() { return metadata.getPort(); }
        public long getTerm() { return metadata.getTerm(); }
        public int getLeader() { return metadata.getLeader(); }
        public long getHb() { return metadata.getHb(); }
        public long getLastSeq() { return metadata.getLastSeq(); }
        public long getLastTerm() { return metadata.getLastTerm(); }
        public int getCount() { return count; }

        public String receivedEvent(int partition) {
            String range = count == 0 ? "vazio" : (getLastSeq() - count + 1) + ".." + getLastSeq();
            return "RECEBIDO;p" + partition + ";termo=" + getTerm() + ";lider=n" + getLeader()
                    + ";hb=" + getHb() + ";ultimoSeq=" + getLastSeq() + ";ultimoTermo=" + getLastTerm()
                    + ";n=" + count + ";seq=" + range + ";cabecalho=true";
        }
    }

    public String getAddress() { return address; }
    public int getPort() { return port; }
    public long getTerm() { return term; }
    public int getLeader() { return leader; }
    public long getHb() { return hb; }
    public long getLastSeq() { return lastSeq; }
    public long getLastTerm() { return lastTerm; }
    public List<SensorRecord> getRecords() { return records; }

    public String receivedEvent(int partition) {
        String range = records.isEmpty() ? "vazio"
                : records.get(0).getSeq() + ".." + records.get(records.size() - 1).getSeq();
        return "RECEBIDO;p" + partition + ";termo=" + term + ";lider=n" + leader + ";hb=" + hb
                + ";ultimoSeq=" + lastSeq + ";ultimoTermo=" + lastTerm
                + ";n=" + records.size() + ";seq=" + range;
    }
}
