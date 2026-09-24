package smtarchive.smt;

import java.util.List;

/**
 * Replayed SMT-LIB commands. Every command keeps its verbatim text and 1-based line,
 * so the original command stream is always recoverable. Unknown commands are kept
 * explicitly rather than silently dropped.
 */
public sealed interface Command permits
        Command.SetLogic, Command.SetOption, Command.DeclareSort, Command.DeclareFun,
        Command.DefineFun, Command.Assert, Command.Push, Command.Pop,
        Command.CheckSat, Command.CheckSatAssuming, Command.GetModel,
        Command.GetUnsatCore, Command.GetAssertions, Command.Unknown, Command.Malformed {

    record SetLogic(String logic, String raw, int line) implements Command {}
    record SetOption(String name, String value, String raw, int line) implements Command {}
    record DeclareSort(String name, int arity, String raw, int line) implements Command {}
    record DeclareFun(String name, List<Sort> argSorts, Sort result, String raw, int line) implements Command {}
    record DefineFun(String name, List<Param> params, Sort result, Term body,
                     boolean recursive, String raw, int line) implements Command {
        public record Param(String name, Sort sort) {}
    }
    record Assert(Term term, String label, String raw, int line) implements Command {}
    record Push(int levels, String raw, int line) implements Command {}
    record Pop(int levels, String raw, int line) implements Command {}
    record CheckSat(String raw, int line) implements Command {}
    record CheckSatAssuming(List<PropLiteral> assumptions, String raw, int line) implements Command {
        /** propositional literal: named assertion or boolean symbol with optional polarity */
        public record PropLiteral(String name, boolean polarity) {}
    }
    record GetModel(String raw, int line) implements Command {}
    record GetUnsatCore(String raw, int line) implements Command {}
    record GetAssertions(String raw, int line) implements Command {}
    record Unknown(String verb, String raw, int line) implements Command {}
    record Malformed(String reason, String raw, int line) implements Command {}

    final class Builder {
        public static Command from(SExpr expr) {
            if (!(expr instanceof SExpr.List l) || l.items().isEmpty()) {
                int ln = expr instanceof SExpr.Atom a ? a.line() : 1;
                return new Malformed("command is not a parenthesised list", expr.toString(), ln);
            }
            if (!(l.items().get(0) instanceof SExpr.Atom ha)) {
                return new Malformed("command head is not a symbol", expr.toString(), l.line());
            }
            String verb = SExpr.unquote(ha.token());
            int line = l.line();
            String raw = expr.toString();
            try {
                return switch (verb) {
                    case "set-logic" -> new SetLogic(atom(l, 1), raw, line);
                    case "set-option" -> new SetOption(
                            l.items().size() > 1 && l.items().get(1) instanceof SExpr.Atom oa ? SExpr.unquote(oa.token()) : "?",
                            l.items().size() > 2 && l.items().get(2) instanceof SExpr.Atom va ? SExpr.unquote(va.token()) : "",
                            raw, line);
                    case "declare-sort" -> new DeclareSort(atom(l, 1),
                            l.items().size() > 2 && l.items().get(2) instanceof SExpr.Atom na
                                    ? Integer.parseInt(SExpr.unquote(((SExpr.Atom) l.items().get(2)).token())) : 0,
                            raw, line);
                    case "declare-const" -> declareConst(l, raw, line);
                    case "declare-fun" -> {
                        String name = atom(l, 1);
                        List<Sort> args = l.items().size() > 3 && l.items().get(2) instanceof SExpr.List al
                                ? al.items().stream().map(Sort::fromSExpr).toList() : List.of();
                        Sort res = l.items().size() > 3 ? Sort.fromSExpr(l.items().get(l.items().size() - 1)) : Sort.UNKNOWN;
                        yield new DeclareFun(name, args, res, raw, line);
                    }
                    case "define-fun", "define-fun-rec" -> defineFun(l, raw, line, verb.endsWith("-rec"));
                    case "assert" -> {
                        Term t = Term.Builder.from(l.items().get(1));
                        String label = null;
                        if (t instanceof Term.Annot an && an.attrs().containsKey("named")) label = an.attrs().get("named");
                        yield new Assert(t, label, raw, line);
                    }
                    case "push" -> new Push(count(l), raw, line);
                    case "pop" -> new Pop(count(l), raw, line);
                    case "check-sat" -> new CheckSat(raw, line);
                    case "check-sat-assuming" -> {
                        List<CheckSatAssuming.PropLiteral> assumptions = new java.util.ArrayList<>();
                        if (l.items().size() > 1 && l.items().get(1) instanceof SExpr.List al) {
                            for (SExpr it : al.items()) {
                                if (it instanceof SExpr.Atom aa) {
                                    String tk = SExpr.unquote(aa.token());
                                    if (tk.startsWith("~")) assumptions.add(new CheckSatAssuming.PropLiteral(tk.substring(1), false));
                                    else assumptions.add(new CheckSatAssuming.PropLiteral(tk, true));
                                } else if (it instanceof SExpr.List nl && nl.items().size() == 2
                                        && "not".equals(nl.headToken()) && nl.items().get(1) instanceof SExpr.Atom na) {
                                    assumptions.add(new CheckSatAssuming.PropLiteral(SExpr.unquote(na.token()), false));
                                }
                            }
                        }
                        yield new CheckSatAssuming(assumptions, raw, line);
                    }
                    case "get-model" -> new GetModel(raw, line);
                    case "get-unsat-core" -> new GetUnsatCore(raw, line);
                    case "get-assertions" -> new GetAssertions(raw, line);
                    default -> new Unknown(verb, raw, line);
                };
            } catch (RuntimeException ex) {
                return new Malformed("malformed " + verb + ": " + ex.getMessage(), raw, line);
            }
        }

        private static Command declareConst(SExpr.List l, String raw, int line) {
            String name = atom(l, 1);
            Sort res = l.items().size() > 2 ? Sort.fromSExpr(l.items().get(2)) : Sort.UNKNOWN;
            return new DeclareFun(name, List.of(), res, raw, line);
        }

        private static Command defineFun(SExpr.List l, String raw, int line, boolean recursive) {
            String name = atom(l, 1);
            List<DefineFun.Param> params = new java.util.ArrayList<>();
            if (l.items().size() > 2 && l.items().get(2) instanceof SExpr.List pl) {
                for (SExpr p : pl.items()) {
                    if (p instanceof SExpr.List pp && pp.items().size() == 2
                            && pp.items().get(0) instanceof SExpr.Atom pa) {
                        params.add(new DefineFun.Param(SExpr.unquote(pa.token()), Sort.fromSExpr(pp.items().get(1))));
                    }
                }
            }
            // (define-fun name (params) result body)
            int resultIdx = l.items().size() - 2;
            Sort result = resultIdx >= 3 ? Sort.fromSExpr(l.items().get(resultIdx)) : Sort.UNKNOWN;
            return new DefineFun(name, params, result, Term.Builder.from(l.items().get(l.items().size() - 1)),
                    recursive, raw, line);
        }

        private static int count(SExpr.List l) {
            if (l.items().size() == 1) return 1;
            if (l.items().get(1) instanceof SExpr.Atom a) return Integer.parseInt(SExpr.unquote(a.token()));
            return 1;
        }

        private static String atom(SExpr.List l, int i) {
            return i < l.items().size() && l.items().get(i) instanceof SExpr.Atom a
                    ? SExpr.unquote(a.token()) : "?";
        }
    }
}
