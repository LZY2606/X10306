package chamber.util;

/** Virtual-time clock: all timestamps in the system come from here so tests are deterministic. */
public interface Clock {
    long nowMillis();

    static Clock system() {
        return System::currentTimeMillis;
    }

    /** Deterministic clock for tests and case replay: starts at a fixed epoch, ticks by step. */
    final class Virtual implements Clock {
        private long now;
        private final long step;

        public Virtual(long startMillis, long stepMillis) {
            this.now = startMillis;
            this.step = stepMillis;
        }

        public static Virtual standard() {
            // fixed virtual epoch: 2026-01-01T00:00:00Z
            return new Virtual(1767225600000L, 1000L);
        }

        @Override
        public synchronized long nowMillis() {
            long t = now;
            now += step;
            return t;
        }
    }
}
