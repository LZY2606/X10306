package witnessroom.util;

/** Logical clock: no wall-clock time anywhere, so replays are deterministic. */
public interface Clock {
    long tick();

    final class Virtual implements Clock {
        private long now;
        public Virtual(long start) { this.now = start; }
        public synchronized long tick() { return ++now; }
        public long peek() { return now; }
    }
}
