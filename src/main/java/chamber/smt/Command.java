package chamber.smt;

import java.util.List;

/** A single SMT-LIB command. The original s-expression is always retained for raw fidelity. */
public sealed interface Command {

    SExpr raw();

    record SetLogic(String logic, SExpr raw) implements Command {}
    record SetInfo(String key, String value, SExpr raw) implements Command {}
    record SetOption(String key, String value, SExpr raw) implements Command {}
    record DeclareConst(String name, String sort, SExpr raw) implements Command {}
    record DeclareFun(String name, List<String> argSorts, String sort, SExpr raw) implements Command {}
    record DefineFun(String name, String sort, SExpr body, SExpr raw) implements Command {}
    /** @param name optional :named annotation */
    record AssertCmd(SExpr term, String name, SExpr raw) implements Command {}
    record Push(int n, SExpr raw) implements Command {}
    record Pop(int n, SExpr raw) implements Command {}
    record CheckSat(SExpr raw) implements Command {}
    record CheckSatAssuming(List<SExpr> literals, SExpr raw) implements Command {}
    record GetModel(SExpr raw) implements Command {}
    record GetUnsatCore(SExpr raw) implements Command {}
    /** Anything else: preserved verbatim, marked unsupported so it is never silently dropped. */
    record Other(String head, SExpr raw) implements Command {}

    final class CommandException extends RuntimeException {
        public CommandException(String message) { super(message); }
    }

    static Command parse(SExpr e) {
        List<SExpr> items = SExpr.list(e);
        if (items == null || items.isEmpty()) {
            throw new CommandException("expected command list at line " + e.line());
        }
        String head = SExpr.atom(items.get(0));
        if (head == null) throw new CommandException("command head must be an atom at line " + e.line());
        try {
            switch (head) {
                case "set-logic" -> {
                    return new SetLogic(reqAtom(items, 1, head), e);
                }
                case "set-info" -> {
                    return new SetInfo(reqAtom(items, 1, head), items.size() > 2 ? items.get(2).toString() : "", e);
                }
                case "set-option" -> {
                    return new SetOption(reqAtom(items, 1, head), items.size() > 2 ? items.get(2).toString() : "", e);
                }
                case "declare-const" -> {
                    return new DeclareConst(reqAtom(items, 1, head), sortOf(items.get(2)), e);
                }
                case "declare-fun" -> {
                    List<SExpr> args = SExpr.list(items.get(2));
                    if (args == null) throw new CommandException("declare-fun arg sorts must be a list");
                    return new DeclareFun(reqAtom(items, 1, head),
                            args.stream().map(Command::sortOf).toList(), sortOf(items.get(3)), e);
                }
                case "define-fun" -> {
                    // (define-fun f ((x S)...) R body) ; only nullary supported in the finite subset
                    List<SExpr> params = SExpr.list(items.get(2));
                    if (params == null || !params.isEmpty()) {
                        throw new CommandException("only nullary define-fun supported in finite subset");
                    }
                    return new DefineFun(reqAtom(items, 1, head), sortOf(items.get(3)), items.get(4), e);
                }
                case "assert" -> {
                    SExpr term = items.get(1);
                    String name = null;
                    // (! term :named n)
                    List<SExpr> bang = SExpr.list(term);
                    if (bang != null && bang.size() >= 4 && "!".equals(SExpr.atom(bang.get(0)))) {
                        for (int i = 2; i + 1 < bang.size(); i++) {
                            if (":named".equals(SExpr.atom(bang.get(i)))) {
                                name = SExpr.atom(bang.get(i + 1));
                            }
                        }
                        term = bang.get(1);
                    }
                    return new AssertCmd(term, name, e);
                }
                case "push" -> {
                    return new Push(Integer.parseInt(reqAtom(items, 1, head)), e);
                }
                case "pop" -> {
                    return new Pop(Integer.parseInt(reqAtom(items, 1, head)), e);
                }
                case "check-sat" -> {
                    return new CheckSat(e);
                }
                case "check-sat-assuming" -> {
                    List<SExpr> lits = SExpr.list(items.get(1));
                    if (lits == null) throw new CommandException("check-sat-assuming expects a literal list");
                    return new CheckSatAssuming(List.copyOf(lits), e);
                }
                case "get-model" -> {
                    return new GetModel(e);
                }
                case "get-unsat-core" -> {
                    return new GetUnsatCore(e);
                }
                default -> {
                    return new Other(head, e);
                }
            }
        } catch (IndexOutOfBoundsException ex) {
            throw new CommandException("malformed " + head + " command at line " + e.line());
        } catch (NumberFormatException ex) {
            throw new CommandException("bad number in " + head + " at line " + e.line());
        }
    }

    private static String reqAtom(List<SExpr> items, int i, String head) {
        String a = SExpr.atom(items.get(i));
        if (a == null) throw new CommandException(head + " expects an atom at position " + i);
        return a;
    }

    static String sortOf(SExpr e) {
        String a = SExpr.atom(e);
        if (a != null) return a;
        List<SExpr> l = SExpr.list(e);
        if (l != null && !l.isEmpty()) return e.toString();
        throw new CommandException("bad sort at line " + e.line());
    }
}
