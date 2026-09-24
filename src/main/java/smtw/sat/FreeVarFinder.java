package smtw.sat;

import smtw.smt.Sexpr;

import java.util.LinkedHashSet;
import java.util.Set;

/** Collects the free symbol occurrences of a term (anything that is not a keyword/builtin/literal). */
public final class FreeVarFinder {

    private FreeVarFinder() {}

    private static final Set<String> BUILTIN = Set.of(
            "and", "or", "not", "=>", "xor", "=", "distinct", "ite", "true", "false",
            "+", "-", "*", "/", "div", "mod", "abs", "<", "<=", ">", ">=",
            "select", "store", "const", "!", "as", ":named");

    public static Set<String> find(Sexpr term) {
        Set<String> out = new LinkedHashSet<>();
        go(term, out);
        return out;
    }

    private static void go(Sexpr t, Set<String> out) {
        if (t instanceof Sexpr.Atom a) {
            String s = a.token();
            if (!BUILTIN.contains(s) && !s.matches("-?\\d+") && !s.matches("-?\\d+\\.\\d+")
                    && !s.matches("-?\\d+/\\d+") && !s.startsWith(":") && !s.startsWith("|")) {
                out.add(s);
            }
            return;
        }
        Sexpr.List l = (Sexpr.List) t;
        boolean named = !l.items().isEmpty() && l.items().get(0) instanceof Sexpr.Atom h
                && "!".equals(h.token());
        for (int i = 0; i < l.items().size(); i++) {
            if (named && i == 0) continue;
            go(l.items().get(i), out);
        }
    }
}
