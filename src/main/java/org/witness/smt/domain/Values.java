package org.witness.smt.domain;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.witness.smt.domain.Sort.ArraySort;

/** Runtime values used by model replay and the bounded solver. */
public final class Values {

    private Values() {
    }

    public interface Value {
        Sort sort();
    }

    public record BoolV(boolean value) implements Value {
        @Override public Sort sort() { return Sort.BOOL; }
        @Override public String toString() { return value ? "true" : "false"; }
    }

    public record NumV(BigRational value, boolean integral) implements Value {
        public static NumV ofInt(long v) {
            return new NumV(BigRational.integer(v), true);
        }

        public static NumV of(BigRational v, boolean integral) {
            return new NumV(v, integral);
        }

        @Override public Sort sort() { return integral ? Sort.INT : Sort.REAL; }
        @Override public String toString() { return value.toString(); }
    }

    /**
     * Finite array value: an explicit default plus overrides. This is the form
     * solver models use for partial arrays; the default records "what everything
     * else is".
     */
    public record ArrayV(ArraySort sort, Map<Value, Value> overrides, Value defaultValue) implements Value {
        public ArrayV {
            overrides = Map.copyOf(overrides);
        }

        public static ArrayV empty(ArraySort sort, Value defaultValue) {
            return new ArrayV(sort, new LinkedHashMap<>(), defaultValue);
        }

        public Value select(Value index) {
            Value v = overrides.get(index);
            return v != null ? v : defaultValue;
        }
    }

    /** A value the local subset cannot determine (missing model entry, unsupported theory...). */
    public record UnknownV(Sort sort, String reason) implements Value {
        @Override public String toString() { return "#unknown:" + reason; }
    }

    public static Value unknown(Sort sort, String reason) {
        return new UnknownV(sort, reason);
    }

    public static boolean truthy(Value v) {
        return v instanceof BoolV b && b.value;
    }

    public static String show(Value v) {
        return v == null ? "null" : v.toString();
    }

    public static NumV num(BigInteger value) {
        return NumV.of(BigRational.of(value, BigInteger.ONE), true);
    }
}
