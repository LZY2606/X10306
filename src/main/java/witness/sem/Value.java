package witness.sem;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Values of the local finite semantic subset.
 * Int is window-bounded; arrays are Int->Int with an explicit default value.
 * UNKNOWN = missing from a partial model or out-of-window; UNSUPPORTED = theory
 * outside the local subset (Real, BitVec, quantifiers, ...). Both are first-class.
 */
public final class Value {
    public enum Kind { BOOL, INT, ARRAY, UNKNOWN, UNSUPPORTED }

    public final Kind kind;
    public final Boolean bool;
    public final Long integer;
    public final long arrayDefault;
    public final Map<Long, Long> arrayEntries;
    public final String note;

    private Value(Kind kind, Boolean bool, Long integer, long arrayDefault, Map<Long, Long> entries, String note) {
        this.kind = kind; this.bool = bool; this.integer = integer;
        this.arrayDefault = arrayDefault;
        this.arrayEntries = entries == null ? Map.of() : Map.copyOf(entries);
        this.note = note;
    }

    public static Value bool(boolean b) { return new Value(Kind.BOOL, b, null, 0, null, null); }
    public static Value integer(long v) { return new Value(Kind.INT, null, v, 0, null, null); }
    public static Value array(long def, Map<Long, Long> entries) {
        return new Value(Kind.ARRAY, null, null, def, entries == null ? new LinkedHashMap<>() : entries, null);
    }
    public static Value unknown(String note) { return new Value(Kind.UNKNOWN, null, null, 0, null, note); }
    public static Value unsupported(String note) { return new Value(Kind.UNSUPPORTED, null, null, 0, null, note); }

    public boolean isConcrete() { return kind == Kind.BOOL || kind == Kind.INT || kind == Kind.ARRAY; }

    public long select(long idx) {
        Long v = arrayEntries.get(idx);
        return v != null ? v : arrayDefault; // array default value semantics
    }

    @Override public String toString() {
        return switch (kind) {
            case BOOL -> bool.toString();
            case INT -> integer.toString();
            case ARRAY -> "array(default=" + arrayDefault + ", entries=" + arrayEntries + ")";
            case UNKNOWN -> "unknown(" + note + ")";
            case UNSUPPORTED -> "unsupported(" + note + ")";
        };
    }
}
