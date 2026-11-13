package raft04;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

public class SensorRecord {
    private final long seq;
    private final long term;
    private final String sensorId;
    private final double temperature;
    private final String timestamp;
    private final long lamport;
    private final VectorClock vector;

    public SensorRecord(long seq, long term, String sensorId, double temperature, String timestamp,
                        long lamport, VectorClock vector) {
        if (seq < 1) {
            throw new IllegalArgumentException("Sequência inválida: " + seq);
        }
        if (term < 1) {
            throw new IllegalArgumentException("Termo inválido: " + term);
        }
        if (sensorId == null || sensorId.isBlank() || sensorId.indexOf(';') >= 0
                || sensorId.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new IllegalArgumentException("Sensor inválido: " + sensorId);
        }
        if (!Double.isFinite(temperature)) {
            throw new IllegalArgumentException("Temperatura não finita");
        }
        if (timestamp == null || timestamp.isBlank()) {
            throw new IllegalArgumentException("Instante ausente");
        }
        try {
            LocalDateTime.parse(timestamp);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Instante inválido: " + timestamp, e);
        }
        if (lamport < 0) {
            throw new IllegalArgumentException("Lamport negativo: " + lamport);
        }
        if (vector == null) {
            throw new IllegalArgumentException("Vetor ausente");
        }
        VectorClock snapshot = vector.copy();
        for (int i = 0; i < snapshot.size(); i++) {
            if (snapshot.get(i) < 0) {
                throw new IllegalArgumentException("Componente vetorial negativa: " + i);
            }
        }
        this.seq = seq;
        this.term = term;
        this.sensorId = sensorId;
        this.temperature = temperature;
        this.timestamp = timestamp;
        this.lamport = lamport;
        this.vector = snapshot;
    }

    // cria um registo com o instante atual (usado só pelo líder)
    public static SensorRecord now(long seq, long term, String sensorId, double temperature,
                                   long lamport, VectorClock vector) {
        return new SensorRecord(seq, term, sensorId, temperature, LocalDateTime.now().withNano(0).toString(),
                lamport, vector);
    }

    // representação textual: é o que se guarda no ficheiro e o que se envia pela rede
    public String toLine() {
        return seq + ";" + term + ";" + sensorId + ";" + temperature + ";" + timestamp + ";" + lamport + ";" + vector;
    }

    public static SensorRecord fromLine(String line) {
        if (line == null) {
            throw new IllegalArgumentException("Registo ausente");
        }
        String[] f = line.split(";", -1);
        if (f.length != 7) {
            throw new IllegalArgumentException("Registo mal formado: " + line);
        }
        return new SensorRecord(Long.parseLong(f[0]), Long.parseLong(f[1]), f[2], Double.parseDouble(f[3]), f[4],
                Long.parseLong(f[5]), parseVector(f[6]));
    }

    // Sem N neste contexto: valida a forma; a dimensão esperada cabe ao chamador.
    private static VectorClock parseVector(String text) {
        if (text.isBlank()) {
            throw new IllegalArgumentException("Vetor vazio");
        }
        String[] components = text.split(",", -1);
        long[] values = new long[components.length];
        for (int i = 0; i < components.length; i++) {
            values[i] = Long.parseLong(components[i]);
        }
        return new VectorClock(values);
    }

    public long getSeq() { return seq; }
    public long getTerm() { return term; }
    public String getSensorId() { return sensorId; }
    public double getTemperature() { return temperature; }
    public String getTimestamp() { return timestamp; }
    public long getLamport() { return lamport; }
    public VectorClock getVector() { return vector.copy(); }
}
