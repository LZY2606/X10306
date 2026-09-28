package witness.util;

import java.time.Instant;

/** Pluggable clock; tests use a fixed virtual clock, production uses wall time. */
public interface Clock {
    Instant now();

    static Clock system() {
        return Instant::now;
    }

    static Clock fixed(String iso) {
        Instant t = Instant.parse(iso);
        return () -> t;
    }
}
