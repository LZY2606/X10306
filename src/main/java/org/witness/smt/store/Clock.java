package org.witness.smt.store;

/** Injectable clock so tests run on virtual time. */
public interface Clock {
    long nowMillis();

    static Clock system() {
        return System::currentTimeMillis;
    }

    static Clock fixed(long value) {
        return () -> value;
    }

    /** Virtual clock advanced explicitly by tests; deterministic across machines. */
    final class Virtual implements Clock {
        private long t;

        public Virtual(long start) {
            this.t = start;
        }

        public void advance(long delta) {
            t += delta;
        }

        @Override
        public long nowMillis() {
            return t;
        }
    }
}
