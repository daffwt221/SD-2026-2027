package rep03;

import java.util.Arrays;

public final class VectorClock {
    private final long[] values;

    public VectorClock(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Tamanho inválido: " + size);
        }
        this.values = new long[size];
    }

    public VectorClock(long[] values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("Vetor inválido");
        }
        this.values = Arrays.copyOf(values, values.length);
    }

    public int size() {
        return values.length;
    }

    public long get(int index) {
        return values[index];
    }

    public void increment(int index) {
        values[index] = Math.addExact(values[index], 1);
    }

    public void merge(VectorClock other) {
        if (other == null || other.size() != size()) {
            throw new IllegalArgumentException("Relógios incompatíveis");
        }

        for (int i = 0; i < values.length; i++) {
            values[i] = Math.max(values[i], other.values[i]);
        }
    }

    public VectorClock copy() {
        return new VectorClock(values);
    }

    public long[] toArray() {
        return Arrays.copyOf(values, values.length);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) text.append(',');
            text.append(values[i]);
        }
        return text.toString();
    }
}
