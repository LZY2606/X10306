package com.witness.smt.sexpr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * S-expression: either an atom (token, kept verbatim) or a list of children.
 * The original token text is always preserved on atoms.
 */
public sealed interface SExpr permits SExpr.Atom, SExpr.List {

    String raw();

    record Atom(String text) implements SExpr {
        @Override public String raw() { return text; }
        @Override public String toString() { return text; }
    }

    record List(java.util.List<SExpr> items) implements SExpr {
        public List { items = Collections.unmodifiableList(new ArrayList<>(items)); }
        @Override public String raw() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(items.get(i).raw());
            }
            return sb.append(')').toString();
        }
        public SExpr head() { return items.get(0); }
        public boolean is(String op) {
            return !items.isEmpty() && items.get(0) instanceof Atom a && a.text.equals(op);
        }
        public java.util.List<SExpr> args() { return items.subList(1, items.size()); }
        @Override public String toString() { return raw(); }
    }

    static Atom atom(String t) { return new Atom(t); }
    static List list(java.util.List<SExpr> xs) { return new List(xs); }
}
