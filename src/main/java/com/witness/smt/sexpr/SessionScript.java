package com.witness.smt.sexpr;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses an SMT-LIB script into an ordered command list.
 * Order, nesting level, named assertions and the logic declaration are all preserved.
 */
public final class SessionScript {

    public static List<Command> parse(String script) {
        List<SExpr> forms = SExprParser.parseAll(script);
        List<Command> out = new ArrayList<>();
        for (SExpr form : forms) {
            out.add(toCommand(out.size(), form));
        }
        return out;
    }

    private static Command toCommand(int index, SExpr form) {
        String raw = form.raw();
        if (!(form instanceof SExpr.List list) || list.items().isEmpty()
                || !(list.head() instanceof SExpr.Atom head)) {
            return new Command(index, Command.Kind.OTHER, raw, listOrNull(form), null, null, null, null);
        }
        String op = head.text();
        List<SExpr> args = list.args();
        switch (op) {
            case "set-logic":
                return new Command(index, Command.Kind.SET_LOGIC, raw, list,
                        args.isEmpty() ? null : args.get(0).raw(), null, null, null);
            case "declare-fun": {
                if (args.size() < 3) return other(index, raw, list);
                String name = args.get(0).raw();
                String sort = args.get(2).raw();
                boolean constLike = args.get(1) instanceof SExpr.List ps && ps.items().isEmpty();
                return new Command(index, constLike ? Command.Kind.DECLARE_CONST : Command.Kind.DECLARE_FUN,
                        raw, list, name, sort, null, null);
            }
            case "declare-const": {
                if (args.size() < 2) return other(index, raw, list);
                return new Command(index, Command.Kind.DECLARE_CONST, raw, list,
                        args.get(0).raw(), args.get(1).raw(), null, null);
            }
            case "define-fun": {
                if (args.size() < 4) return other(index, raw, list);
                return new Command(index, Command.Kind.DEFINE_FUN, raw, list,
                        args.get(0).raw(), args.get(2).raw(), args.get(3), null);
            }
            case "assert": {
                if (args.isEmpty()) return other(index, raw, list);
                SExpr term = args.get(0);
                String label = null;
                // (assert (! term :named lbl))
                if (term instanceof SExpr.List bang && bang.is("!") && bang.items().size() >= 3) {
                    for (int i = 2; i + 1 < bang.items().size(); i++) {
                        if (bang.items().get(i) instanceof SExpr.Atom a && a.text().equals(":named")) {
                            label = bang.items().get(i + 1).raw();
                        }
                    }
                    term = bang.items().get(1);
                }
                return new Command(index, Command.Kind.ASSERT, raw, list, label, null, term, null);
            }
            case "push":
                return new Command(index, Command.Kind.PUSH, raw, list, null, null, null, null);
            case "pop":
                return new Command(index, Command.Kind.POP, raw, list, null, null, null, null);
            case "check-sat":
                return new Command(index, Command.Kind.CHECK_SAT, raw, list, null, null, null, null);
            case "check-sat-assuming": {
                SExpr.List lits = args.isEmpty() ? SExpr.list(List.of())
                        : (args.get(0) instanceof SExpr.List l ? l : SExpr.list(List.of(args.get(0))));
                return new Command(index, Command.Kind.CHECK_SAT_ASSUMING, raw, list,
                        null, null, null, lits);
            }
            case "get-model":
                return new Command(index, Command.Kind.GET_MODEL, raw, list, null, null, null, null);
            case "get-unsat-core":
                return new Command(index, Command.Kind.GET_UNSAT_CORE, raw, list, null, null, null, null);
            default:
                return new Command(index, Command.Kind.OTHER, raw, list, null, null, null, null);
        }
    }

    private static Command other(int index, String raw, SExpr.List list) {
        return new Command(index, Command.Kind.OTHER, raw, list, null, null, null, null);
    }

    private static SExpr.List listOrNull(SExpr f) {
        return f instanceof SExpr.List l ? l : null;
    }
}
