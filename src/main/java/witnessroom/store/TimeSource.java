package witnessroom.store;

/** Injectable clock so tests can run on virtual time. */
public interface TimeSource {
    String nowIso();

    static TimeSource system() {
        return () -> java.time.Instant.now().toString();
    }

    /** Deterministic virtual clock for tests. */
    final class Virtual implements TimeSource {
        private long tick;
        public Virtual(long startEpochSeconds) { this.tick = startEpochSeconds; }
        public synchronized String nowIso() {
            return java.time.Instant.ofEpochSecond(tick++).toString();
        }
    }
}
