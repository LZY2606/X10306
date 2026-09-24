package smtwitness.smtlib;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Values produced by the local semantics.
 * A value is either a boolean, an integer, a string, an array with an explicit
 * default and ordered stores, or UNKNOWN (partial models / unsupported leaves).
 */
public sealed interface Value permits Value.BoolV, Value.IntV, Value.StrV, Value.ArrV, Value.Unknown {
    Sort sort();

    String raw();

    record BoolV(boolean v) implements Value {
        @Override public Sort sort() { return Sort.named("Bool"); }
        @Override public String raw() { return Boolean.toString(v); }
    }

    record IntV(long v, String original) implements Value {
        public IntV(long v) {
            this(v, Long.toString(v));
        }
        @Override public Sort sort() { return Sort.named("Int"); }
        @Override public String raw() { return original; }
    }

    record StrV(String v) implements Value {
        @Override public Sort sort() { return Sort.named("String"); }
        @Override public String raw() { return v; }
    }

    final class ArrV implements Value {
        private final Sort indexSort;
        private final Sort elementSort;
        private final Value defaultValue;
        private final LinkedHashMap<Value, Value> stores;

        public ArrV(Sort indexSort, Sort elementSort, Value defaultValue, LinkedHashMap<Value, Value> stores) {
            this.indexSort = indexSort;
            this.elementSort = elementSort;
            this.defaultValue = defaultValue;
            this.stores = new LinkedHashMap<>(stores);
        }

        public Value defaultValue() {
            return defaultValue;
        }

        public Map<Value, Value> stores() {
            return stores;
        }

        public Value select(Value index) {
            Value hit = stores.get(index);
            return hit != null ? hit : defaultValue;
        }

        @Override
        public Sort sort() {
            return Sort.array(indexSort, elementSort);
        }

        @Override
        public String raw() {
            StringBuilder sb = new StringBuilder();
            Value cur = defaultValue;
            sb.append("((as const ").append(Sort.array(indexSort, elementSort)).append(") ")
              .append(cur.raw()).append(')');
            for (Map.Entry<Value, Value> e : stores.entrySet()) {
                sb.insert(0, "(store ");
                sb.append(' ').append(e.getKey().raw()).append(' ').append(e.getValue().raw()).append(')');
            }
            return sb.toString();
        }
    }

    record Unknown(String reason) implements Value {
        @Override public Sort sort() { return Sort.named("Unknown"); }
        @Override public String raw() { return "unknown:" + reason; }
    }

    static Value fromBool(boolean b) {
        return new BoolV(b);
    }

    static Value truth() {
        return new BoolV(true);
    }
}
