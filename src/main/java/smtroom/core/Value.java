package smtroom.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

/** Values in the finite semantic subset. Unknown and Unsupported are first-class. */
public sealed interface Value {

    record VBool(boolean b) implements Value {}
    record VInt(BigInteger n) implements Value {}
    record VReal(BigDecimal d) implements Value {}
    /** Array value: explicit entries over a default value. Keys are printed index values. */
    record VArray(Value defaultValue, Map<String, Value> entries) implements Value {
        public Value select(Value idx) {
            Value v = entries.get(Value.show(idx));
            return v != null ? v : defaultValue;
        }
    }
    /** Partial model or missing information. */
    record VUnknown(String reason) implements Value {}
    /** Construct outside the supported local subset (theory flagged explicitly). */
    record VUnsupported(String reason) implements Value {}

    static Value unknown(String reason) { return new VUnknown(reason); }
    static Value unsupported(String reason) { return new VUnsupported(reason); }

    default boolean isBool(boolean b) { return this instanceof VBool x && x.b() == b; }

    static String show(Value v) {
        return switch (v) {
            case VBool b -> b.b() ? "true" : "false";
            case VInt i -> i.n().toString();
            case VReal r -> r.d().stripTrailingZeros().toPlainString();
            case VArray a -> {
                StringBuilder sb = new StringBuilder("(array default=").append(show(a.defaultValue()));
                a.entries().forEach((k, val) -> sb.append(" [").append(k).append("]:=").append(show(val)));
                yield sb.append(")").toString();
            }
            case VUnknown u -> "UNKNOWN(" + u.reason() + ")";
            case VUnsupported u -> "UNSUPPORTED(" + u.reason() + ")";
        };
    }

    static Map<String, Value> arrayEntries() { return new LinkedHashMap<>(); }
}
