package witnessroom.smt;

import java.util.List;
import java.util.StringJoiner;

public sealed interface SExpr {
    record Atom(String value) implements SExpr {}
    record Lst(List<SExpr> items) implements SExpr {}

    static Atom atom(String v) { return new Atom(v); }

    static String render(SExpr e) {
        if (e instanceof Atom a) return a.value();
        Lst l = (Lst) e;
        StringJoiner j = new StringJoiner(" ", "(", ")");
        for (SExpr item : l.items()) j.add(render(item));
        return j.toString();
    }

    static boolean isAtom(SExpr e, String v) {
        return e instanceof Atom a && a.value().equals(v);
    }
}
