package smtwitness.time;

/** Time source abstraction so replay can use deterministic virtual time. */
public interface Clock {
    long nowMillis();

    static Clock system() {
        return System::currentTimeMillis;
    }

    static Clock virtual(long startMillis, long stepMillis) {
        return new Clock() {
            private long t = startMillis;

            @Override
            public long nowMillis() {
                long v = t;
                t += stepMillis;
                return v;
            }
        };
    }
}
