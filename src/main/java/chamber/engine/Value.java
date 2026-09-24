package chamber.engine;

import java.util.Map;
import java.util.TreeMap;

/** A value in the local finite semantic subset. Unknown is a first-class value:
 *  it propagates through evaluation instead of being silently coerced. */
public sealed interface Value {

    record B(boolean v) implements Value {}
    record I(long v) implements Value {}

    /** Finite array value: explicit stores over a default value (array default semantics). */
    record Arr(TreeMap<Long, Long> stores, long defaultValue) implements Value {
        public long select(long idx) {
            return stores.getOrDefault(idx, defaultValue);
        }
    }

    /** Unknown: partial model, unsupported theory, or unevaluable term. Carries the reason. */
    record Unknown(String reason) implements Value {}

    static Unknown unknown(String reason) { return new Unknown(reason); }

    default boolean isUnknown() { return this instanceof Unknown; }

    static Value parse(String atom) {
        if (atom.equals("true")) return new B(true);
        if (atom.equals("false")) return new B(false);
        try {
            return new I(Long.parseLong(atom));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    default String display() {
        if (this instanceof B b) return Boolean.toString(b.v());
        if (this instanceof I i) return Long.toString(i.v());
        if (this instanceof Arr a) return "array" + a.stores() + " else " + a.defaultValue();
        if (this instanceof Unknown u) return "unknown(" + u.reason() + ")";
        return "?";
    }

    @SuppressWarnings("unchecked")
    static Value fromModel(Object o) {
        // used when rebuilding values from stored JSON
        if (o instanceof Boolean b) return new B(b);
        if (o instanceof Number n) return new I(n.longValue());
        if (o instanceof Map<?, ?> m) {
            TreeMap<Long, Long> stores = new TreeMap<>();
            Object st = m.get("stores");
            if (st instanceof Map<?, ?> sm) {
                for (Map.Entry<?, ?> e : sm.entrySet()) {
                    stores.put(Long.parseLong(String.valueOf(e.getKey())),
                            ((Number) e.getValue()).longValue());
                }
            }
            long def = m.get("default") instanceof Number d ? d.longValue() : 0;
            return new Arr(stores, def);
        }
        return new Unknown("unreadable model value");
    }

    default Object toJson() {
        if (this instanceof B b) return b.v();
        if (this instanceof I i) return i.v();
        if (this instanceof Arr a) return Map.of("stores", a.stores(), "default", a.defaultValue());
        if (this instanceof Unknown u) return Map.of("unknown", u.reason());
        return null;
    }
}
