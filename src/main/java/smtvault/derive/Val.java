package smtvault.derive;

/** Normalized scalar/array value in the local semantic subset. */
public final class Val {
    public enum Kind { BOOL, INT, ARRAY, OTHER }

    public final Kind kind;
    public final Boolean bool;
    public final Long integer;
    public final String raw; // original representation retained
    public final Val arrayDefault;
    public final java.util.Map<Val, Val> arrayStores;

    private Val(Kind kind, Boolean bool, Long integer, String raw, Val arrayDefault,
                java.util.Map<Val, Val> arrayStores) {
        this.kind = kind;
        this.bool = bool;
        this.integer = integer;
        this.raw = raw;
        this.arrayDefault = arrayDefault;
        this.arrayStores = arrayStores;
    }

    public static Val bool(boolean v) {
        return new Val(Kind.BOOL, v, null, String.valueOf(v), null, null);
    }

    public static Val integer(long v, String raw) {
        return new Val(Kind.INT, null, v, raw, null, null);
    }

    public static Val other(String raw) {
        return new Val(Kind.OTHER, null, null, raw, null, null);
    }

    public static Val array(Val defaultValue, java.util.Map<Val, Val> stores) {
        return new Val(Kind.ARRAY, null, null, "array", defaultValue, stores);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Val other)) {
            return false;
        }
        if (kind != other.kind) {
            return false;
        }
        return switch (kind) {
            case BOOL -> bool.equals(other.bool);
            case INT -> integer.equals(other.integer);
            case OTHER -> raw.equals(other.raw);
            case ARRAY -> false;
        };
    }

    @Override
    public int hashCode() {
        return kind.hashCode() * 31
                + (bool != null ? bool.hashCode() : integer != null ? integer.hashCode() : raw.hashCode());
    }

    @Override
    public String toString() {
        return raw;
    }
}
