package witness;

/** Time source. Production uses SYSTEM; tests use a virtual clock so runs are deterministic. */
public interface Clock {
    long nowMillis();

    Clock SYSTEM = System::currentTimeMillis;

    final class Virtual implements Clock {
        private long now;
        public Virtual(long start) { this.now = start; }
        @Override public long nowMillis() { return now; }
        public void advance(long ms) { now += ms; }
    }
}
