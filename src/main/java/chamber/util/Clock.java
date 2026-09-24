package chamber.util;

import java.time.Instant;

/** Time source. Production uses the system clock; tests use a virtual clock. */
public interface Clock {
    Instant now();

    static Clock system() {
        return Instant::now;
    }

    /** Deterministic clock for tests and replay: starts at a fixed epoch, ticks 1s per call. */
    static Clock virtual() {
        return new Clock() {
            private long tick = 0;
            @Override
            public synchronized Instant now() {
                return Instant.ofEpochSecond(1_700_000_000L + (tick++));
            }
        };
    }
}
