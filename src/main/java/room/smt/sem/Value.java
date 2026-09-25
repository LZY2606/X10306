package room.smt.sem;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A value in the local finite semantic subset. */
public final class Value {
    public enum Kind { BOOL, INT, ARRAY, UNKNOWN, UNSUPPORTED }

    public final Kind kind;
    public final Boolean bool;
    public final Long num;
    public final Map<Long, Long> arrayEntries; // ARRAY: explicit store entries
    public final Long arrayDefault;            // ARRAY: default value
    public final String reason;                // UNKNOWN / UNSUPPORTED explanation

    private Value(Kind k, Boolean b, Long n, Map<Long, Long> e, Long d, String r) {
        kind = k; bool = b; num = n; arrayEntries = e; arrayDefault = d; reason = r;
    }

    public static Value bool(boolean b) { return new Value(Kind.BOOL, b, null, null, null, null); }
    public static Value num(long n) { return new Value(Kind.INT, null, n, null, null, null); }
    public static Value array(Map<Long, Long> entries, long dflt) {
        return new Value(Kind.ARRAY, null, null, new LinkedHashMap<>(entries), dflt, null);
    }
    public static Value unknown(String reason) { return new Value(Kind.UNKNOWN, null, null, null, null, reason); }
    public static Value unsupported(String reason) { return new Value(Kind.UNSUPPORTED, null, null, null, null, reason); }

    public boolean isBool() { return kind == Kind.BOOL; }
    public boolean isTrue() { return kind == Kind.BOOL && bool; }
    public boolean isFalse() { return kind == Kind.BOOL && !bool; }

    public String display() {
        return switch (kind) {
            case BOOL -> bool.toString();
            case INT -> num.toString();
            case ARRAY -> "(array default=" + arrayDefault + " entries=" + arrayEntries + ")";
            case UNKNOWN -> "unknown(" + reason + ")";
            case UNSUPPORTED -> "unsupported(" + reason + ")";
        };
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Value v)) return false;
        return kind == v.kind && Objects.equals(bool, v.bool) && Objects.equals(num, v.num)
                && Objects.equals(arrayEntries, v.arrayEntries) && Objects.equals(arrayDefault, v.arrayDefault);
    }
    @Override public int hashCode() { return Objects.hash(kind, bool, num, arrayEntries, arrayDefault); }
    @Override public String toString() { return display(); }
}
