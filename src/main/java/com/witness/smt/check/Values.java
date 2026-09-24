package com.witness.smt.check;

import java.util.LinkedHashMap;
import java.util.Map;

/** Values of the locally supported finite semantic subset: Bool, Int, arrays with a default. */
public sealed interface Value permits Value.BoolV, Value.IntV, Value.ArrayV {

    record BoolV(boolean b) implements Value {
        @Override public String toString() { return Boolean.toString(b); }
    }

    record IntV(long n) implements Value {
        @Override public String toString() { return Long.toString(n); }
    }

    /** Array value: sparse store with an explicit default (from {@code (as const ...)}). */
    final class ArrayV implements Value {
        private final Map<Long, Value> stores;
        private final Value def;

        public ArrayV(Map<Long, Value> stores, Value def) {
            this.stores = new LinkedHashMap<>(stores);
            this.def = def;
        }

        public static ArrayV constant(Value def) {
            return new ArrayV(Map.of(), def);
        }

        public Value select(long index) {
            Value v = stores.get(index);
            return v != null ? v : def;
        }

        public ArrayV store(long index, Value v) {
            Map<Long, Value> next = new LinkedHashMap<>(stores);
            next.put(index, v);
            return new ArrayV(next, def);
        }

        public Value def() { return def; }
        public Map<Long, Value> stores() { return Map.copyOf(stores); }

        @Override public String toString() {
            return "(array (default " + def + ") " + stores + ")";
        }
    }
}
