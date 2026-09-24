package smtwitness.smtlib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A minimal S-expression: either an atom or a fixed-order list of children. */
public sealed interface SExpr permits SExpr.Atom, SExpr.List {
    String raw();

    record Atom(String raw) implements SExpr {
        @Override public String toString() { return raw; }
    }

    record List(List<SExpr> children) implements SExpr {
        public List { children = Collections.unmodifiableList(new ArrayList<>(children)); }
        public int size() { return children.size(); }
        public SExpr get(int i) { return children.get(i); }
        @Override public String raw() { return toString(); }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(children.get(i));
            }
            return sb.append(')').toString();
        }
    }

    /** Convenience accessor for the leading keyword/operator symbol of a list form. */
    default String head() {
        if (this instanceof List l && !l.children().isEmpty() && l.get(0) instanceof Atom a) {
            return a.raw();
        }
        return null;
    }
}
