package witness.smt;

import java.util.List;
import java.util.Objects;

/** Finite subset of SMT-LIB sorts understood by the local semantics. */
public abstract class Sort {
    public static final Sort BOOL = new Atom("Bool");
    public static final Sort INT = new Atom("Int");
    public static final Sort REAL = new Atom("REAL_UNSUPPORTED");

    public static Sort atom(String name) { return new Atom(name); }
    public static Sort array(Sort key, Sort value) { return new Arr(key, value); }

    public abstract String show();

    @Override public String toString() { return show(); }

    public static final class Atom extends Sort {
        public final String name;
        Atom(String name) { this.name = name; }
        @Override public String show() { return name; }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Atom && ((Atom) o).name.equals(name)); }
        @Override public int hashCode() { return Objects.hashCode(name); }
    }

    public static final class Arr extends Sort {
        public final Sort key, value;
        Arr(Sort key, Sort value) { this.key = key; this.value = value; }
        @Override public String show() { return "(Array " + key.show() + " " + value.show() + ")"; }
        @Override public boolean equals(Object o) {
            return this == o || (o instanceof Arr && ((Arr) o).key.equals(key) && ((Arr) o).value.equals(value));
        }
        @Override public int hashCode() { return Objects.hash(key, value); }
    }

    /** Sorts referencing strings/bitvectors/reals or unknown identifiers are kept as unsupported markers. */
    public static final class Unsupported extends Sort {
        public final String raw;
        public final String reason;
        public Unsupported(String raw, String reason) { this.raw = raw; this.reason = reason; }
        @Override public String show() { return raw; }
        public String reason() { return reason; }
        @Override public boolean equals(Object o) { return this == o || (o instanceof Unsupported && ((Unsupported) o).raw.equals(raw)); }
        @Override public int hashCode() { return Objects.hashCode(raw); }
    }

    public static Sort fromSexpr(witness.sexpr.SExpr s, List<String> diag) {
        if (s.isAtom()) {
            String n = s.atom();
            switch (n) {
                case "Bool": return BOOL;
                case "Int": return INT;
                case "Real": return new Unsupported("Real", "theory RealArith not in supported subset");
                case "String": return new Unsupported("String", "theory Strings not in supported subset");
                default: return new Atom(n); // uninterpreted / enum sort
            }
        }
        if (s.head() != null && s.head().equals("Array") && s.size() == 3) {
            Sort k = fromSexpr(s.get(1), diag);
            Sort v = fromSexpr(s.get(2), diag);
            if (k instanceof Unsupported || v instanceof Unsupported) {
                String reason = (k instanceof Unsupported ? ((Unsupported) k).reason : ((Unsupported) v).reason);
                return new Unsupported(s.toString(), reason);
            }
            return array(k, v);
        }
        if (s.head() != null && (s.head().equals("_") || s.head().equals("BitVec"))) {
            return new Unsupported(s.toString(), "theory FixedSizeBitVectors not in supported subset");
        }
        diag.add("unsupported sort expression: " + s);
        return new Unsupported(s.toString(), "unrecognized sort expression");
    }
}
