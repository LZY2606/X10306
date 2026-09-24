package smtw.smt;

import java.util.ArrayList;
import java.util.List;

/** S-expression: either an atom token or a list of sub-expressions. Raw text is preserved at atom level. */
public sealed interface Sexpr permits Sexpr.Atom, Sexpr.List {

    String raw();

    record Atom(String token) implements Sexpr {
        @Override public String raw() { return token; }
        @Override public String toString() { return token; }
    }

    record List(java.util.List<Sexpr> items) implements Sexpr {
        public List(java.util.List<Sexpr> items) { this.items = java.util.List.copyOf(items); }
        @Override public String raw() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(items.get(i).raw());
            }
            return sb.append(')').toString();
        }
        @Override public String toString() { return raw(); }
        public Sexpr head() { return items.get(0); }
        public String headToken() { return ((Atom) items.get(0)).token(); }
        public java.util.List<Sexpr> args() { return items.subList(1, items.size()); }
    }

    static Atom atom(String t) { return new Atom(t); }

    static Sexpr.List list(Sexpr... xs) {
        return new Sexpr.List(java.util.List.of(xs));
    }

    static boolean isAtom(Sexpr s) { return s instanceof Atom; }

    static String token(Sexpr s) { return ((Atom) s).token(); }

    static Sexpr.List asList(Sexpr s) { return (Sexpr.List) s; }

    /** True when this list starts with the given keyword head, e.g. "assert". */
    static boolean is(Sexpr s, String head) {
        return s instanceof Sexpr.List l && !l.items().isEmpty()
                && l.items().get(0) instanceof Atom a && a.token().equals(head);
    }
}
