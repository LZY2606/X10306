package witnessroom.smt;

import java.math.BigInteger;
import java.util.Map;
import java.util.TreeMap;

/** Values in the local finite semantic subset. Raw representations are kept. */
public sealed interface Value {
    record BoolV(boolean v) implements Value {}
    record IntV(BigInteger v) implements Value {}
    /** Real values keep the original literal plus a normalized decimal. */
    record RealV(String raw, double approx) implements Value {}
    /** Arrays carry an explicit default value plus point overrides. */
    record ArrayV(Value dflt, Map<String, Value> entries) implements Value {
        public ArrayV {
            entries = new TreeMap<>(entries);
        }
    }
    /** A value we cannot interpret: kept verbatim with its sort and a diagnostic. */
    record RawV(String raw, String sort, String diagnostic) implements Value {}

    static String render(Value v) {
        if (v instanceof BoolV b) return String.valueOf(b.v());
        if (v instanceof IntV i) return i.v().toString();
        if (v instanceof RealV r) return r.raw();
        if (v instanceof RawV r) return r.raw();
        ArrayV a = (ArrayV) v;
        StringBuilder sb = new StringBuilder("(const ").append(render(a.dflt())).append(")");
        for (Map.Entry<String, Value> e : a.entries().entrySet())
            sb.append("[").append(e.getKey()).append(" -> ").append(render(e.getValue())).append("]");
        return sb.toString();
    }
}
