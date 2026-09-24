package witness;

import java.util.Random;

/** Fixed-seed randomness source; recorded in parameter fingerprints for reproducibility. */
public final class SeededRng {
    private SeededRng() {}

    public static Random of(long seed) {
        return new Random(seed);
    }
}
