package smtw.model;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/** Result values produced by the local evaluator. UNDEF and ERROR are first-class, never hidden. */
public sealed interface Val permits Val.BoolV, Val.IntV, Val.RealV, Val.ArrV,
        Val.UndefV, Val.ErrorV, Val.UnsupportedV {

    /** Exact integer (arbitrary precision). */
    record IntV(BigInteger v) implements Val {
        public static IntV of(long x) { return new IntV(BigInteger.valueOf(x)); }
        @Override public String toString() { return v.toString(); }
    }

    /** Rational number p/q for Real sort (q > 0). */
    record RealV(BigInteger p, BigInteger q) implements Val {
        public RealV {
            Objects.requireNonNull(p);
            Objects.requireNonNull(q);
            if (q.signum() == 0) throw new IllegalArgumentException("zero denominator");
            if (q.signum() < 0) { p = p.negate(); q = q.negate(); }
        }
        public double asDouble() { return p.doubleValue() / q.doubleValue(); }
        public String toDecString() {
            var dec = new java.math.BigDecimal(p).divide(new java.math.BigDecimal(q),
                    java.math.MathContext.DECIMAL64);
            return dec.stripTrailingZeros().toPlainString();
        }
        @Override public String toString() {
            if (q.equals(BigInteger.ONE)) return p.toString();
            return "(/ " + p + " " + q + ")";
        }
    }

    record BoolV(boolean b) implements Val {
        public static final BoolV TRUE = new BoolV(true);
        public static final BoolV FALSE = new BoolV(false);
        public static BoolV of(boolean b) { return b ? TRUE : FALSE; }
        @Override public String toString() { return Boolean.toString(b); }
    }

    /**
     * Array value with an explicit DEFAULT (SMT const-array semantics); stores are an ordered
     * association list of index->value. Select of an un-stored index returns the default.
     */
    record ArrV(Val defaultValue, List<Store> stores) implements Val {
        public ArrV { stores = List.copyOf(stores); }
        public record Store(Val index, Val value) {}

        public Val select(Val idx) {
            for (int i = stores.size() - 1; i >= 0; i--) {
                if (valsEqual(stores.get(i).index(), idx)) return stores.get(i).value();
            }
            return defaultValue;
        }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("(as const " + defaultValue + ")");
            for (Store s : stores) sb.append(" [").append(s.index()).append("->").append(s.value()).append("]");
            return sb.toString();
        }
    }

    /** Partial model: the symbol/function case was not defined, so the value is not determined. */
    record UndefV(String reason) implements Val {
        @Override public String toString() { return "UNDEF(" + reason + ")"; }
    }

    record ErrorV(String message) implements Val {
        @Override public String toString() { return "ERROR(" + message + ")"; }
    }

    /** Term uses a construct outside the local subset. */
    record UnsupportedV(String reason) implements Val {
        @Override public String toString() { return "UNSUPPORTED(" + reason + ")"; }
    }

    static boolean valsEqual(Val a, Val b) {
        if (a instanceof IntV x && b instanceof IntV y) return x.v().compareTo(y.v()) == 0;
        if (a instanceof RealV x && b instanceof RealV y)
            return x.p().multiply(y.q()).compareTo(y.p().multiply(x.q())) == 0;
        if (a instanceof IntV x && b instanceof RealV y)
            return new RealV(x.v(), BigInteger.ONE).p().multiply(y.q())
                    .compareTo(y.p().multiply(new RealV(x.v(), BigInteger.ONE).q())) == 0;
        if (a instanceof BoolV x && b instanceof BoolV y) return x.b() == y.b();
        return a.equals(b);
    }

    static boolean isDetermined(Val v) {
        return v instanceof BoolV || v instanceof IntV || v instanceof RealV || v instanceof ArrV;
    }
}
