package witnessroom.smt;

import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime value in the local fragment. */
public sealed interface Value permits Value.BoolV, Value.NumV, Value.ArrayV, Value.UnsupportedV {

    Value TRUE = new BoolV(true);
    Value FALSE = new BoolV(false);

    record BoolV(boolean b) implements Value {
        @Override public String toString() { return b ? "true" : "false"; }
    }

    record NumV(Rational r, boolean real) implements Value {
        @Override public String toString() {
            return r.toString() + (real && r.isIntegral() ? ".0" : "");
        }
    }

    /** Persistent functional array: explicit index map plus a (possibly array-typed) default. */
    final class ArrayV implements Value {
        private final Map<Value, Value> stores;
        private final Value defaultValue;

        public ArrayV(Value defaultValue) {
            this.stores = Map.of();
            this.defaultValue = defaultValue;
        }

        private ArrayV(Map<Value, Value> stores, Value defaultValue) {
            this.stores = stores;
            this.defaultValue = defaultValue;
        }

        public Value get(Value index) {
            Value v = stores.get(index);
            if (v != null) return v;
            if (defaultValue instanceof ArrayV nested) return nested.get(index);
            return defaultValue;
        }

        public ArrayV set(Value index, Value val) {
            Map<Value, Value> m = new LinkedHashMap<>(stores);
            m.put(index, val);
            return new ArrayV(Map.copyOf(m), defaultValue);
        }

        public Value defaultValue() { return defaultValue; }
        public Map<Value, Value> storesView() { return stores; }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder("(store* (as const ");
            sb.append(defaultValue).append(")");
            for (Map.Entry<Value, Value> e : stores.entrySet()) {
                sb.append(' ').append(e.getKey()).append(' ').append(e.getValue());
            }
            return sb.append(')').toString();
        }
    }

    /** Placeholder for a value whose sort is outside the verified fragment. */
    record UnsupportedV(String raw, String reason) implements Value {
        @Override public String toString() { return raw; }
    }

    static Value num(Rational r, boolean real) { return new NumV(r, real); }
}
