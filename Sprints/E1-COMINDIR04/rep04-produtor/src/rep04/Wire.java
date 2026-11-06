package rep04;

public final class Wire {
    public static final double LIMIAR_ALARME = 28.0;
    public static final int LIMITE_RETIDOS = 10;
    public static final int T_TCP_MS = 5000;
    public static final int PORTO_BASE = 7000;
    public static final long PERIODO_HB_MS = Long.getLong("rep04.periodo", 5000L);
    public static final int FATOR_TIMEOUT = Integer.getInteger("rep04.fator", 3);
    public static final long VERIFICACAO_MS = 1000L;

    private Wire() { }

    public static String topicIn(int k) { return "rep04-in-p" + k; }
    public static String topicData(int k) { return "rep04-p" + k; }

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
            throw new IllegalArgumentException("rep04.periodo e rep04.fator devem ser positivos");
        }
        try {
            Math.multiplyExact(Math.multiplyExact(PERIODO_HB_MS, FATOR_TIMEOUT), 1_000_000L);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Timeout demasiado grande", e);
        }
    }
}
