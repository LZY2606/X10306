package witness.util;

import java.util.Random;

/** Deterministic random source for candidate sampling. Fixed seed keeps runs reproducible. */
public final class SeededRng {
    private final Random random;

    public SeededRng(long seed) {
        this.random = new Random(seed);
    }

    public long nextLong() {
        return random.nextLong();
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    public boolean nextBoolean() {
        return random.nextBoolean();
    }
}
