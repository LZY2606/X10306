package witness.smt;

import java.util.ArrayList;
import java.util.List;

/** AST for the locally supported term language; unsupported constructs keep raw text. */
public abstract class Term {
    public abstract Sort sort();
    public abstract String show();
    @Override public String toString() { return show(); }

    public static final class BoolLit extends Term {
        public final boolean value;
        public BoolLit(boolean value) { this.value = value; }
        public Sort sort() { return Sort.BOOL; }
        public String show() { return value ? "true" : "false"; }
    }

    public static final class IntLit extends Term {
        public final java.math.BigInteger value;
        public final String raw;
        public IntLit(java.math.BigInteger value, String raw) { this.value = value; this.raw = raw; }
        public Sort sort() { return Sort.INT; }
        public String show() { return raw; }
    }

    public static final class EnumLit extends Term {
        public final String sortName, ctor;
        public EnumLit(String sortName, String ctor) { this.sortName = sortName; this.ctor = ctor; }
        public Sort sort() { return Sort.atom(sortName); }
        public String show() { return ctor; }
    }

    public static final class Var extends Term {
        public final String name;
        public final Sort declared;
        public Var(String name, Sort declared) { this.name = name; this.declared = declared; }
        public Sort sort() { return declared; }
        public String show() { return name; }
    }

    public static final class App extends Term {
        public final String op;
        public final List<Term> args;
        public final Sort result;
        public App(String op, List<Term> args, Sort result) { this.op = op; this.args = args; this.result = result; }
        public Sort sort() { return result; }
        public String show() {
            StringBuilder sb = new StringBuilder("(").append(op);
            for (Term a : args) sb.append(' ').append(a.show());
            return sb.append(')').toString();
        }
    }

    /** Quantifier / theory construct the local subset cannot evaluate. */
    public static final class Unsupported extends Term {
        public final String raw, reason;
        public final Sort result;
        public Unsupported(String raw, String reason) {
            this.raw = raw; this.reason = reason; this.result = new Sort.Unsupported(raw, reason);
        }
        public Sort sort() { return result; }
        public String show() { return raw; }
        public String reason() { return reason; }
    }

    public static List<String> freeUnsupported(Term t, List<String> out) {
        if (t instanceof Unsupported) out.add(((Unsupported) t).raw + " :: " + ((Unsupported) t).reason);
        else if (t instanceof App) for (Term a : ((App) t).args) freeUnsupported(a, out);
        return out;
    }

    public static boolean containsUnsupported(Term t) {
        if (t instanceof Unsupported) return true;
        if (t instanceof App) {
            for (Term a : ((App) t).args) if (containsUnsupported(a)) return true;
        }
        return false;
    }

    public static List<Term> children(Term t) {
        List<Term> out = new ArrayList<>();
        if (t instanceof App) out.addAll(((App) t).args);
        return out;
    }
}
