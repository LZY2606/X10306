package witness;

/** Pluggable clock so tests use virtual time and production uses wall time. */
public interface Clock {
    long nowMillis();

    Clock SYSTEM = System::currentTimeMillis;

    static Clock virtual(long startMillis) {
        return new Clock() {
            private long t = startMillis;
            public synchronized long nowMillis() { t += 1L; return t; }
        };
    }
}
