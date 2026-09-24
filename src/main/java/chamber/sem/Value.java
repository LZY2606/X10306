package chamber.sem;

import java.math.BigInteger;
import java.util.TreeMap;

/** A value in the local finite semantic subset. Unknown carries a diagnostic reason
 *  and is a first-class result, never silently dropped. */
public sealed interface Value {

    record B(boolean v) implements Value {
        @Override public String toString() { return Boolean.toString(v); }
    }

    record I(BigInteger v) implements Value {
        @Override public String toString() { return v.toString(); }
    }

    record R(Fraction v) implements Value {
        @Override public String toString() { return v.toString(); }
    }

    /** Finite array: default value plus explicit entries. Basis records the element sort. */
    record Arr(Value dflt, TreeMap<BigInteger, Value> entries, String indexBasis, String elemBasis) implements Value {
        public Value select(BigInteger idx) {
            return entries.getOrDefault(idx, dflt);
        }
        public Arr store(BigInteger idx, Value v) {
            TreeMap<BigInteger, Value> copy = new TreeMap<>(entries);
            copy.put(idx, v);
            return new Arr(dflt, copy, indexBasis, elemBasis);
        }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("(as const ").append(dflt);
            entries.forEach((k, v) -> sb.append(" [").append(k).append(" -> ").append(v).append("]"));
            return sb.append(")").toString();
        }
    }

    record Unknown(String reason) implements Value {
        @Override public String toString() { return "<unknown: " + reason + ">"; }
    }

    static Value bool(boolean b) { return new B(b); }
    static Value integer(BigInteger i) { return new I(i); }
    static Value real(Fraction f) { return new R(f); }
    static Value unknown(String reason) { return new Unknown(reason); }

    default boolean isUnknown() { return this instanceof Unknown; }
}
