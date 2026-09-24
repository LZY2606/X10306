package smtw.smt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Splits a raw SMT-LIB transcript into commands while preserving each command's exact source text. */
public final class TranscriptParser {

    private TranscriptParser() {}

    public static List<SmtCommand> parse(String transcript) {
        List<Sexpr> exprs = SexprParser.parseAll(transcript);
        List<SmtCommand> out = new ArrayList<>();
        int ordinal = 0;
        for (Sexpr e : exprs) {
            List<String> errs = new ArrayList<>();
            CommandKind kind;
            if (!(e instanceof Sexpr.List l)) {
                errs.add("top-level expression must be a list");
                kind = CommandKind.OTHER;
            } else {
                kind = classify(l, errs);
            }
            out.add(new SmtCommand(ordinal++, e.raw(),
                    e instanceof Sexpr.List l ? l : new Sexpr.List(List.of(e)), kind, List.copyOf(errs)));
        }
        return out;
    }

    private static CommandKind classify(Sexpr.List l, List<String> errs) {
        if (l.items().isEmpty()) {
            errs.add("empty command list");
            return CommandKind.OTHER;
        }
        if (!(l.items().get(0) instanceof Sexpr.Atom h)) {
            errs.add("command head must be a symbol");
            return CommandKind.OTHER;
        }
        String head = h.token().toLowerCase(Locale.ROOT);
        CommandKind kind = switch (head) {
            case "set-logic" -> { validateArity(head, l, 1, errs); yield CommandKind.SET_LOGIC; }
            case "set-option" -> CommandKind.SET_OPTION;
            case "declare-sort" -> { validateArity(head, l, 2, errs); yield CommandKind.DECLARE_SORT; }
            case "define-sort" -> CommandKind.DEFINE_SORT;
            case "declare-const" -> { validateArity(head, l, 2, errs); yield CommandKind.DECLARE_CONST; }
            case "declare-fun" -> { validateArity(head, l, 3, errs); yield CommandKind.DECLARE_FUN; }
            case "define-fun" -> { validateArity(head, l, 4, errs); yield CommandKind.DEFINE_FUN; }
            case "assert" -> { validateArity(head, l, 1, errs); yield CommandKind.ASSERT; }
            case "push" -> CommandKind.PUSH;
            case "pop" -> CommandKind.POP;
            case "check-sat" -> CommandKind.CHECK_SAT;
            case "check-sat-assuming" -> { validateArity(head, l, 1, errs); yield CommandKind.CHECK_SAT_ASSUMING; }
            case "get-model" -> CommandKind.GET_MODEL;
            case "get-unsat-core" -> CommandKind.GET_UNSAT_CORE;
            case "reset" -> CommandKind.RESET;
            case "exit" -> CommandKind.EXIT;
            case "echo" -> CommandKind.ECHO;
            default -> CommandKind.OTHER;
        };
        if ((kind == CommandKind.PUSH || kind == CommandKind.POP)) {
            if (l.items().size() > 2) errs.add(head + " takes at most one numeric argument");
        }
        return kind;
    }

    private static void validateArity(String head, Sexpr.List l, int expectedArgs, List<String> errs) {
        if (l.items().size() - 1 != expectedArgs) {
            errs.add(head + " expects " + expectedArgs + " argument(s), found " + (l.items().size() - 1));
        }
    }
}
