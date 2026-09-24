package org.witness.smt.parse;

import java.util.ArrayList;
import java.util.List;

/** A parsed SMT-LIB s-expression: either an atom token or a parenthesised list. */
public sealed interface SExpr permits SExpr.Atom, SExpr.List {
    String raw();

    record Atom(String token) implements SExpr {
        public String raw() { return token; }
        public boolean is(String s) { return token.equals(s); }
    }

    record List(List<SExpr> items) implements SExpr {
        public String raw() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(items.get(i).raw());
            }
            return sb.append(')').toString();
        }
        public SExpr head() { return items.get(0); }
        public String headName() { return items.isEmpty() ? "" : (items.get(0) instanceof Atom a ? a.token() : ""); }
    }

    static Atom atom(String s) { return new Atom(s); }
    static List list(SExpr... xs) { return new List(new ArrayList<>(java.util.Arrays.asList(xs))); }
}
