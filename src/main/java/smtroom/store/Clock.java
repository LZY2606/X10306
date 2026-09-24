package smtroom.store;

import java.time.Instant;

/** Time source. Tests use a virtual clock; production uses system time. */
public interface Clock {
    Instant now();

    static Clock system() { return Instant::now; }

    /** Mutable virtual clock for deterministic tests. */
    final class Virtual implements Clock {
        private Instant t;
        public Virtual(Instant start) { this.t = start; }
        public Instant now() { return t; }
        public void advanceSeconds(long s) { t = t.plusSeconds(s); }
    }
}
