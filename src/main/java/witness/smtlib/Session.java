package witness.smtlib;

import witness.sexpr.Sexpr;
import java.util.ArrayList;
import java.util.List;

/**
 * Parsed SMT-LIB command stream. The original command order, push/pop levels,
 * named assertions, check-sat-assuming literals, model definitions, response
 * order and logic declarations are all preserved verbatim.
 */
public final class Session {

    public enum Kind {
        SET_LOGIC, DECLARE_CONST, DECLARE_FUN, DEFINE_FUN, ASSERT,
        PUSH, POP, CHECK_SAT, CHECK_SAT_ASSUMING,
        GET_MODEL, GET_UNSAT_CORE, GET_VALUE, SET_INFO, SET_OPTION, EXIT, OTHER
    }

    /** One command in the stream; {@code raw} keeps the exact source text. */
    public record Cmd(int index, Kind kind, Sexpr raw, String name, Sexpr term,
                      List<String> assumptions, int depth) {
        public String rawText() { return raw.toString(); }
    }

    public final List<Cmd> commands;
    public final String logic; // declared logic, may be null

    private Session(List<Cmd> commands, String logic) {
        this.commands = commands;
        this.logic = logic;
    }

    public static Session parse(String src) {
        List<Sexpr> toplevel = Sexpr.parseAll(src);
        List<Cmd> cmds = new ArrayList<>();
        String logic = null;
        for (int i = 0; i < toplevel.size(); i++) {
            Sexpr e = toplevel.get(i);
            if (!(e instanceof Sexpr.Lst) || e.size() == 0) {
                cmds.add(new Cmd(i, Kind.OTHER, e, null, null, List.of(), 0));
                continue;
            }
            String head = e.head();
            switch (head) {
                case "set-logic" -> {
                    logic = e.size() > 1 ? e.get(1).toString() : "";
                    cmds.add(new Cmd(i, Kind.SET_LOGIC, e, logic, null, List.of(), 0));
                }
                case "declare-const" ->
                    cmds.add(new Cmd(i, Kind.DECLARE_CONST, e, e.get(1).toString(), e.get(2), List.of(), 0));
                case "declare-fun" ->
                    cmds.add(new Cmd(i, Kind.DECLARE_FUN, e, e.get(1).toString(), e.size() > 3 ? e.get(3) : e.get(2), List.of(), 0));
                case "define-fun" ->
                    cmds.add(new Cmd(i, Kind.DEFINE_FUN, e, e.get(1).toString(), e.get(e.size() - 1), List.of(), 0));
                case "assert" -> {
                    Sexpr term = e.get(1);
                    String name = null;
                    if (term instanceof Sexpr.Lst && term.head().equals("!")) {
                        // (assert (! <term> :named <name> ...))
                        for (int k = 2; k + 1 < term.size(); k++) {
                            if (term.get(k).isAtom() && term.get(k).atom().equals(":named")) {
                                name = term.get(k + 1).toString();
                            }
                        }
                        term = term.get(1);
                    }
                    cmds.add(new Cmd(i, Kind.ASSERT, e, name, term, List.of(), 0));
                }
                case "push" -> cmds.add(new Cmd(i, Kind.PUSH, e, null, null, List.of(),
                        e.size() > 1 ? Integer.parseInt(e.get(1).atom()) : 1));
                case "pop" -> cmds.add(new Cmd(i, Kind.POP, e, null, null, List.of(),
                        e.size() > 1 ? Integer.parseInt(e.get(1).atom()) : 1));
                case "check-sat" -> cmds.add(new Cmd(i, Kind.CHECK_SAT, e, null, null, List.of(), 0));
                case "check-sat-assuming" -> {
                    List<String> lits = new ArrayList<>();
                    if (e.size() > 1 && e.get(1) instanceof Sexpr.Lst lits_) {
                        for (Sexpr lit : lits_.items) lits.add(lit.toString());
                    }
                    cmds.add(new Cmd(i, Kind.CHECK_SAT_ASSUMING, e, null, null, List.copyOf(lits), 0));
                }
                case "get-model" -> cmds.add(new Cmd(i, Kind.GET_MODEL, e, null, null, List.of(), 0));
                case "get-unsat-core" -> cmds.add(new Cmd(i, Kind.GET_UNSAT_CORE, e, null, null, List.of(), 0));
                case "get-value" -> cmds.add(new Cmd(i, Kind.GET_VALUE, e, null, null, List.of(), 0));
                case "set-info" -> cmds.add(new Cmd(i, Kind.SET_INFO, e, null, null, List.of(), 0));
                case "set-option" -> cmds.add(new Cmd(i, Kind.SET_OPTION, e, null, null, List.of(), 0));
                case "exit" -> cmds.add(new Cmd(i, Kind.EXIT, e, null, null, List.of(), 0));
                default -> cmds.add(new Cmd(i, Kind.OTHER, e, null, null, List.of(), 0));
            }
        }
        return new Session(List.copyOf(cmds), logic);
    }

    public List<Cmd> checks() {
        List<Cmd> out = new ArrayList<>();
        for (Cmd c : commands) {
            if (c.kind() == Kind.CHECK_SAT || c.kind() == Kind.CHECK_SAT_ASSUMING) out.add(c);
        }
        return out;
    }
}
