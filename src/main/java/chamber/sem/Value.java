package chamber.sem;

import java.util.Map;
import java.util.TreeMap;

/**
 * Values of the local finite semantic subset.
 * Unknown and Unsupported are first-class: they propagate and are never silently coerced.
 */
public sealed interface Value {

    record VBool(boolean b) implements Value {
        @Override public String toString() { return Boolean.toString(b); }
    }

    /** Integers are evaluated exactly within 64-bit bounds; overflow yields VUnknown. */
    record VInt(long v) implements Value {
        @Override public String toString() { return Long.toString(v); }
    }

    /** Finite array: explicit stores over a constant default value. */
    record VArr(long defaultValue, TreeMap<Long, Long> stores) implements Value {
        public long select(long i) { return stores.getOrDefault(i, defaultValue); }
        public VArr store(long i, long v) {
            TreeMap<Long, Long> copy = new TreeMap<>(stores);
            copy.put(i, v);
            return new VArr(defaultValue, copy);
        }
        @Override public String toString() {
            return "array(default=" + defaultValue + ", stores=" + stores + ")";
        }
    }

    /** A value the model did not provide or that finite evaluation cannot determine. */
    record VUnknown(String reason) implements Value {
        @Override public String toString() { return "unknown(" + reason + ")"; }
    }

    /** A value outside the supported finite subset; the theory is named explicitly. */
    record VUnsupported(String theory) implements Value {
        @Override public String toString() { return "unsupported(" + theory + ")"; }
    }

    static boolean isUnknown(Value v) { return v instanceof VUnknown; }
    static boolean isUnsupported(Value v) { return v instanceof VUnsupported; }

    static Value unknown(String reason) { return new VUnknown(reason); }
    static Value unsupported(String theory) { return new VUnsupported(theory); }

    static Value bool(boolean b) { return new VBool(b); }
    static Value integer(long v) { return new VInt(v); }

    static Value arrConst(long defaultValue) { return new VArr(defaultValue, new TreeMap<>()); }

    /** Equality of two fully-known values; unknown/unsupported propagate. */
    static Value eq(Value a, Value b) {
        if (isUnsupported(a)) return a;
        if (isUnsupported(b)) return b;
        if (isUnknown(a)) return a;
        if (isUnknown(b)) return b;
        if (a instanceof VBool x && b instanceof VBool y) return bool(x.b() == y.b());
        if (a instanceof VInt x && b instanceof VInt y) return bool(x.v() == y.v());
        if (a instanceof VArr x && b instanceof VArr y) {
            if (x.defaultValue() != b.defaultValue()) return bool(false);
            return bool(x.stores().equals(b.stores()));
        }
        return bool(false); // different known sorts: not equal
    }
}
