package chamber;

import java.time.Instant;

/** Time source. Production uses the system clock; tests inject a virtual clock. */
public interface Clock {
    String now();

    static Clock system() {
        return () -> Instant.now().toString();
    }

    /** Deterministic virtual clock: fixed base instant, +1s per tick. */
    final class Virtual implements Clock {
        private long tick;
        private final long baseEpoch;

        public Virtual() {
            this(Instant.parse("2026-01-01T00:00:00Z").getEpochSecond());
        }

        public Virtual(long baseEpoch) {
            this.baseEpoch = baseEpoch;
        }

        @Override
        public synchronized String now() {
            return Instant.ofEpochSecond(baseEpoch + (tick++)).toString();
        }
    }
}
