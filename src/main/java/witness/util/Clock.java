package witness.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual clock used by the whole application. Tests construct the store
 * with a fixed Clock so timestamps and event ordering are deterministic;
 * production code uses {@link #system()} wall-clock time.
 */
public final class Clock {
    private final AtomicLong nowMillis;

    public Clock(long startMillis) {
        this.nowMillis = new AtomicLong(startMillis);
    }

    public static Clock system() {
        return new Clock(System.currentTimeMillis()) {
            @Override public long now() { return System.currentTimeMillis(); }
            @Override public void advance(long millis) { throw new UnsupportedOperationException("system clock"); }
        };
    }

    public long now() {
        return nowMillis.get();
    }

    public void advance(long millis) {
        nowMillis.addAndGet(millis);
    }
}
