package chamber.util;

import java.util.concurrent.atomic.AtomicLong;

/** Deterministic sequential id source. Seeded so tests and case replay produce stable identities. */
public final class Ids {
    private final String prefix;
    private final AtomicLong next;

    public Ids(String prefix, long seed) {
        this.prefix = prefix;
        this.next = new AtomicLong(seed);
    }

    public static Ids seeded() {
        return new Ids("id", 1);
    }

    public String next(String kind) {
        return prefix + "-" + kind + "-" + String.format("%06d", next.getAndIncrement());
    }
}
