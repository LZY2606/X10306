package witnessroom.smt;

/**
 * One raw SMT-LIB command in its original order. The full s-expression is
 * retained verbatim; structured fields are derived views over it.
 */
public record SmtCommand(int index, String raw, SExpr.List sexpr, int line, int col) {

    public String head() {
        return sexpr.headSym();
    }

    /** :named attribute argument, if this command is (assert (! term :named label)). */
    public String namedLabel() {
        if (!"assert".equals(head()) || sexpr.args().size() != 1) return null;
        SExpr inner = sexpr.args().get(0);
        if (inner instanceof SExpr.List l && l.children().size() >= 3
                && "!".equals(l.headSym())) {
            for (int i = 2; i < l.children().size(); i++) {
                SExpr a = l.children().get(i);
                if (a instanceof SExpr.Atom at && at.raw().equals(":named")
                        && i + 1 < l.children().size()
                        && l.children().get(i + 1) instanceof SExpr.Atom name) {
                    return name.raw();
                }
            }
        }
        return null;
    }

    /** Term carried by an assert, stripping the (! term :named label) wrapper. */
    public SExpr assertedTerm() {
        if (!"assert".equals(head())) return null;
        SExpr inner = sexpr.args().get(0);
        if (inner instanceof SExpr.List l && l.children().size() >= 3 && "!".equals(l.headSym())) {
            return l.children().get(1);
        }
        return inner;
    }
}
