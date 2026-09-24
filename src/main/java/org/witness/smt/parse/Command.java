package org.witness.smt.parse;

import java.util.ArrayList;
import java.util.List;

/** Classified SMT-LIB command extracted from a raw command item. */
public record Command(int index, String raw, String name, SExpr.List expr) {

    public static Command classify(int index, String raw) {
        List<SExpr> all = SmtParser.parseAll(raw);
        if (all.size() != 1 || !(all.get(0) instanceof SExpr.List l))
            return new Command(index, raw, "<invalid>", SExpr.list());
        String name = l.headName();
        return new Command(index, raw, name, l);
    }

    public List<SExpr> args() { return expr.items().subList(1, expr.items().size()); }

    /** Body term of an (assert ...). */
    public SExpr assertTerm() { return expr.items().get(1); }

    /**
     * Extract the named-assertion label from (! term :named label), if any.
     * Also returns the wrapped term.
     */
    public NamedAssertion namedAssertion() {
        SExpr term = assertTerm();
        return NamedAssertion.extract(term);
    }

    public record NamedAssertion(String label, SExpr term) {
        public static NamedAssertion extract(SExpr term) {
            if (term instanceof SExpr.List l && l.headName().equals("!") && l.items().size() >= 2) {
                for (int i = 2; i + 1 < l.items().size(); i++) {
                    if (l.items().get(i) instanceof SExpr.Atom a && a.is(":named")
                            && l.items().get(i + 1) instanceof SExpr.Atom label) {
                        return new NamedAssertion(label.token(), l.items().get(1));
                    }
                }
            }
            return new NamedAssertion(null, term);
        }
    }

    /** Assumption labels of a (check-sat-assuming (sym ...)). */
    public List<String> assumptions() {
        List<String> out = new ArrayList<>();
        if (!name.equals("check-sat-assuming")) return out;
        if (args().isEmpty() || !(args().get(0) instanceof SExpr.List l))
            throw new SmtParser.ParseFailure("check-sat-assuming needs a symbol list", 0);
        for (SExpr x : l.items()) {
            if (!(x instanceof SExpr.Atom a))
                throw new SmtParser.ParseFailure("assumption list must contain symbols", 0);
            out.add(a.token());
        }
        return out;
    }
}
