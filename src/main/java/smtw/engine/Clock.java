package smtw.engine;

/** Injectable time source; tests use a deterministic virtual clock, production uses system time. */
public interface Clock {
    long nowMs();

    Clock SYSTEM = System::currentTimeMillis;

    static Clock virtual(long startMs) {
        return new Clock() {
            private long t = startMs;
            @Override public long nowMs() { return ++t; }
            public void advance(long ms) { t += ms; }
        };
    }
}
