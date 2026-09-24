package chamber.smt;

import java.util.List;
import java.util.Set;

/** A typed view over one SMT-LIB command in the supported subset.
 *  Unknown commands are preserved verbatim with a diagnostic rather than dropped. */
public record Command(
        Kind kind,
        SExpr raw,
        String name,          // declare-const / declare-fun / define-fun / :named
        SExpr sort,           // declared sort, if any
        SExpr term,           // assert body / define-fun body
        long count,           // push/pop level count
        List<SExpr> assumptions, // check-sat-assuming literals
        String diagnostic     // non-null when the command is not fully supported
) {
    public enum Kind {
        SET_LOGIC, DECLARE_CONST, DECLARE_FUN, DEFINE_FUN, ASSERT,
        PUSH, POP, CHECK_SAT, CHECK_SAT_ASSUMING, GET_MODEL, GET_UNSAT_CORE,
        SET_OPTION, EXIT, UNKNOWN
    }

    /** Logics whose theories the local finite semantic subset can re-check. */
    private static final Set<String> SUPPORTED_LOGICS = Set.of(
            "QF_LIA", "QF_UFLIA", "QF_ALIA", "QF_AUFLIA", "LIA", "AUFLIA");

    public static boolean isSupportedLogic(String logic) {
        return SUPPORTED_LOGICS.contains(logic);
    }

    public static boolean isSupportedSort(SExpr sort) {
        if (sort instanceof SExpr.Atom a) {
            return a.value().equals("Int") || a.value().equals("Bool");
        }
        List<SExpr> items = sort.asList();
        if (items.size() == 3 && items.get(0) instanceof SExpr.Atom head && head.value().equals("Array")) {
            return isSupportedSort(items.get(1)) && isSupportedSort(items.get(2));
        }
        return false;
    }

    public static Command parse(SExpr e) {
        if (!(e instanceof SExpr.ListExpr l) || l.items().isEmpty()
                || !(l.items().get(0) instanceof SExpr.Atom head)) {
            return new Command(Kind.UNKNOWN, e, null, null, null, 0, List.of(),
                    "not a well-formed command");
        }
        String h = head.value();
        List<SExpr> items = l.items();
        try {
            switch (h) {
                case "set-logic" -> {
                    String logic = items.get(1).asAtom();
                    String diag = isSupportedLogic(logic) ? null
                            : "unsupported theory: logic " + logic + " is outside the local finite semantic subset";
                    return new Command(Kind.SET_LOGIC, e, logic, null, null, 0, List.of(), diag);
                }
                case "declare-const" -> {
                    String name = items.get(1).asAtom();
                    SExpr sort = items.get(2);
                    String diag = isSupportedSort(sort) ? null
                            : "unsupported theory: sort " + sort + " for " + name;
                    return new Command(Kind.DECLARE_CONST, e, name, sort, null, 0, List.of(), diag);
                }
                case "declare-fun" -> {
                    String name = items.get(1).asAtom();
                    List<SExpr> args = items.get(2).asList();
                    SExpr sort = items.get(3);
                    String diag = null;
                    if (!args.isEmpty()) diag = "unsupported: uninterpreted function " + name + " with arguments";
                    else if (!isSupportedSort(sort)) diag = "unsupported theory: sort " + sort + " for " + name;
                    return new Command(Kind.DECLARE_FUN, e, name, sort, null, 0, List.of(), diag);
                }
                case "define-fun" -> {
                    String name = items.get(1).asAtom();
                    SExpr sort = items.get(3);
                    SExpr body = items.size() > 4 ? items.get(4) : null;
                    return new Command(Kind.DEFINE_FUN, e, name, sort, body, 0, List.of(), null);
                }
                case "assert" -> {
                    SExpr body = items.get(1);
                    String named = null;
                    // (assert (! <body> :named <name>))
                    if (body instanceof SExpr.ListExpr bl && bl.items().size() >= 3
                            && bl.items().get(0) instanceof SExpr.Atom bang && bang.value().equals("!")) {
                        for (int i = 2; i + 1 < bl.items().size(); i++) {
                            if (bl.items().get(i) instanceof SExpr.Atom k
                                    && k.value().equals(":named")) {
                                named = bl.items().get(i + 1).asAtom();
                            }
                        }
                        body = bl.items().get(1);
                    }
                    return new Command(Kind.ASSERT, e, named, null, body, 0, List.of(), null);
                }
                case "push" -> {
                    long n = items.size() > 1 ? Long.parseLong(items.get(1).asAtom()) : 1;
                    return new Command(Kind.PUSH, e, null, null, null, n, List.of(), null);
                }
                case "pop" -> {
                    long n = items.size() > 1 ? Long.parseLong(items.get(1).asAtom()) : 1;
                    return new Command(Kind.POP, e, null, null, null, n, List.of(), null);
                }
                case "check-sat" -> {
                    return new Command(Kind.CHECK_SAT, e, null, null, null, 0, List.of(), null);
                }
                case "check-sat-assuming" -> {
                    List<SExpr> lits = items.get(1).asList();
                    return new Command(Kind.CHECK_SAT_ASSUMING, e, null, null, null, 0,
                            List.copyOf(lits), null);
                }
                case "get-model" -> {
                    return new Command(Kind.GET_MODEL, e, null, null, null, 0, List.of(), null);
                }
                case "get-unsat-core" -> {
                    return new Command(Kind.GET_UNSAT_CORE, e, null, null, null, 0, List.of(), null);
                }
                case "set-option", "set-info" -> {
                    return new Command(Kind.SET_OPTION, e, null, null, null, 0, List.of(), null);
                }
                case "exit" -> {
                    return new Command(Kind.EXIT, e, null, null, null, 0, List.of(), null);
                }
                default -> {
                    return new Command(Kind.UNKNOWN, e, null, null, null, 0, List.of(),
                            "unsupported command: " + h + " (preserved verbatim, not executed)");
                }
            }
        } catch (RuntimeException ex) {
            return new Command(Kind.UNKNOWN, e, null, null, null, 0, List.of(),
                    "malformed " + h + ": " + ex.getMessage());
        }
    }
}
