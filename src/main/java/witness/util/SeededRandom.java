package witness.util;

import java.util.Random;

/**
 * Deterministic random source for bounded candidate generation. The fixed
 * seed (see case pack / tests) makes candidate enumeration reproducible.
 */
public final class SeededRandom {
    private final Random random;

    public SeededRandom(long seed) {
        this.random = new Random(seed);
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    public long nextLong(long bound) {
        long bits;
        long value;
        do {
            bits = random.nextLong() & ((1L << 63) - 1);
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0L);
        return value;
    }
}
