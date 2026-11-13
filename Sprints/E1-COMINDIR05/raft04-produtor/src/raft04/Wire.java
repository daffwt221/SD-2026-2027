package raft04;

import java.nio.file.Files;
import java.nio.file.Path;

public final class Wire {
    public static final double LIMIAR_ALARME = 28.0;
    public static final int LIMITE_RETIDOS = 10;
    public static final int T_TCP_MS = 5000;
    public static final int PORTO_BASE = 7000;
    public static final long PERIODO_HB_MS = Long.getLong("raft04.periodo", 2000L);
    public static final int FATOR_TIMEOUT = Integer.getInteger("raft04.fator", 3);
    public static final long VERIFICACAO_MS = 200L;
    public static final long ELEICAO_MIN_MS = Long.getLong("raft04.eleicaoMin", 3 * PERIODO_HB_MS);
    public static final long ELEICAO_MAX_MS = Long.getLong("raft04.eleicaoMax", 6 * PERIODO_HB_MS);
    public static final int GRUPO = 3;

    private Wire() { }

    public static String topicIn(int k) { return "raft04-in-p" + k; }
    public static String topicData(int k) { return "raft04-p" + k; }
    public static String topicElection(int k) { return "raft04-eleicao-p" + k; }

    public static int nodePort(int k, int i) {
        validateNode(k, i);
        long port = PORTO_BASE + (long) k * GRUPO + i - 1;
        if (port > 65535) throw new IllegalArgumentException("Porto do nó fora do intervalo TCP");
        return (int) port;
    }

    public static boolean isolated(int k, int i) {
        validateNode(k, i);
        return Files.exists(Path.of("isolar-p" + k + "-n" + i));
    }

    private static void validateNode(int k, int i) {
        if (k < 0 || i < 1 || i > GRUPO) {
            throw new IllegalArgumentException("Partição ou identificador de nó inválido");
        }
    }

    public static String in(String sensor, double temperature, long lamport, VectorClock vector) {
        return "IN;" + sensor + ";" + temperature + ";" + lamport + ";" + vector;
    }

    public static String type(String msg) {
        String m = msg.trim();
        int i = m.indexOf(';');
        if (i <= 0) {
            throw new IllegalArgumentException("Mensagem sem tipo: " + msg);
        }
        return m.substring(0, i);
    }

    public static String body(String msg) {
        String m = msg.trim();
        return m.substring(m.indexOf(';') + 1);
    }

    public static void validateHeartbeatConfig() {
        if (PERIODO_HB_MS < 1 || FATOR_TIMEOUT < 1) {
            throw new IllegalArgumentException("raft04.periodo e raft04.fator devem ser positivos");
        }
        if (ELEICAO_MIN_MS < 1 || ELEICAO_MAX_MS < ELEICAO_MIN_MS) {
            throw new IllegalArgumentException("Intervalo de eleição inválido");
        }
        try {
            Math.multiplyExact(Math.multiplyExact(PERIODO_HB_MS, FATOR_TIMEOUT), 1_000_000L);
            Math.multiplyExact(ELEICAO_MIN_MS, 1_000_000L);
            Math.multiplyExact(ELEICAO_MAX_MS, 1_000_000L);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Timeout demasiado grande", e);
        }
    }
}
