package chamber.smt;

import chamber.sexpr.Sexpr;
import chamber.sexpr.SexprParser;
import java.util.ArrayList;
import java.util.List;

/** Parses an SMT-LIB command stream. Format errors are explicit and positional;
 *  unrecognised commands are preserved as {@link Command.Unknown}. */
public final class ScriptParser {

    public static final class ScriptException extends RuntimeException {
        public final int commandIndex;
        public ScriptException(String message, int commandIndex) {
            super("command #" + commandIndex + ": " + message);
            this.commandIndex = commandIndex;
        }
    }

    public static List<Command> parse(String sessionText) {
        List<Sexpr> forms;
        try {
            forms = SexprParser.parseAll(sessionText);
        } catch (SexprParser.ParseException e) {
            throw new ScriptException("lexical error: " + e.getMessage(), -1);
        }
        List<Command> out = new ArrayList<>();
        for (int i = 0; i < forms.size(); i++) {
            out.add(toCommand(forms.get(i), i));
        }
        return out;
    }

    private static Command toCommand(Sexpr form, int idx) {
        if (form.isAtom()) throw new ScriptException("bare atom is not a command: " + form, idx);
        List<Sexpr> items = form.asList();
        if (items.isEmpty()) throw new ScriptException("empty command ()", idx);
        Sexpr headExpr = items.get(0);
        if (!headExpr.isAtom()) throw new ScriptException("command head must be a symbol", idx);
        String head = headExpr.asAtom();
        try {
            switch (head) {
                case "set-logic" -> {
                    return new Command.SetLogic(atom(items, 1));
                }
                case "set-info" -> {
                    return new Command.SetInfo(atom(items, 1), render(items.get(2)));
                }
                case "set-option" -> {
                    return new Command.SetOption(atom(items, 1), render(items.get(2)));
                }
                case "declare-const" -> {
                    return new Command.DeclareConst(atom(items, 1), items.get(2));
                }
                case "declare-fun" -> {
                    List<Sexpr> args = items.get(2).asList();
                    return new Command.DeclareFun(atom(items, 1), args, items.get(3));
                }
                case "define-fun" -> {
                    List<Sexpr> args = items.get(2).asList();
                    return new Command.DefineFun(atom(items, 1), args, items.get(3), items.get(4));
                }
                case "assert" -> {
                    Sexpr term = items.get(1);
                    String name = null;
                    if (!term.isAtom()) {
                        List<Sexpr> t = term.asList();
                        if (t.size() >= 3 && t.get(0).isAtom() && t.get(0).asAtom().equals("!")) {
                            for (int k = 2; k + 1 < t.size(); k += 2) {
                                if (t.get(k).isAtom() && t.get(k).asAtom().equals(":named")) {
                                    name = t.get(k + 1).asAtom();
                                }
                            }
                            term = t.get(1);
                        }
                    }
                    return new Command.AssertCmd(term, name);
                }
                case "push" -> {
                    return new Command.Push(items.size() > 1 ? Integer.parseInt(atom(items, 1)) : 1);
                }
                case "pop" -> {
                    return new Command.Pop(items.size() > 1 ? Integer.parseInt(atom(items, 1)) : 1);
                }
                case "check-sat" -> {
                    return new Command.CheckSat();
                }
                case "check-sat-assuming" -> {
                    return new Command.CheckSatAssuming(items.get(1).asList());
                }
                case "get-model" -> {
                    return new Command.GetModel();
                }
                case "get-unsat-core" -> {
                    return new Command.GetUnsatCore();
                }
                case "get-value" -> {
                    return new Command.GetValue(items.get(1).asList());
                }
                case "exit" -> {
                    return new Command.ExitCmd();
                }
                default -> {
                    return new Command.Unknown(head, form);
                }
            }
        } catch (IndexOutOfBoundsException | IllegalStateException | NumberFormatException e) {
            throw new ScriptException("malformed " + head + ": " + e.getMessage(), idx);
        }
    }

    private static String atom(List<Sexpr> items, int i) {
        return items.get(i).asAtom();
    }

    private static String render(Sexpr e) {
        return e.toString();
    }
}
