package room.testimony;

public interface Clock {
    long nowMillis();

    static Clock system() {
        return System::currentTimeMillis;
    }

    final class Virtual implements Clock {
        private long now;
        private final long step;

        public Virtual(long start, long step) {
            this.now = start;
            this.step = step;
        }

        public synchronized long nowMillis() {
            long v = now;
            now += step;
            return v;
        }
    }
}
