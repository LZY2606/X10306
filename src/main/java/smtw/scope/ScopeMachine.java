package smtw.scope;

import smtw.smt.Sexpr;
import smtw.smt.SmtCommand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays a command stream against the SMT-LIB scoping rules:
 *  - push/pop maintain assertion levels; pop restores previous name bindings (incl. same-name shadow)
 *  - named assertions (! t :named p) register a label binding at the current level
 *  - check-sat-assuming literals resolve against CURRENT bindings, only for that single check
 */
public final class ScopeMachine {

    private int level = 0;
    private final List<List<Binding>> stack = new ArrayList<>();
    private final List<ScopeEvent> events = new ArrayList<>();
    private final List<CheckPoint> checkpoints = new ArrayList<>();
    private int bindingSeq = 0;
    private String transcriptId = "t";

    public ScopeMachine() {
        stack.add(new ArrayList<>());
    }

    public Replay replay(List<SmtCommand> commands, String tid) {
        this.transcriptId = tid;
        for (SmtCommand cmd : commands) {
            apply(cmd);
        }
        return new Replay(List.copyOf(events), List.copyOf(checkpoints), level);
    }

    private void apply(SmtCommand cmd) {
        int before = level;
        String action;
        List<String> diag = new ArrayList<>(cmd.parseErrors());
        switch (cmd.kind()) {
            case PUSH -> {
                int n = arityOrDefault(cmd, 1);
                for (int i = 0; i < n; i++) stack.add(new ArrayList<>());
                level += n;
                action = "PUSH(" + n + ")";
            }
            case POP -> {
                int n = arityOrDefault(cmd, 1);
                for (int i = 0; i < n; i++) {
                    if (stack.size() <= 1) {
                        diag.add("pop below level 0 is invalid in SMT-LIB; recorded but not applied");
                        break;
                    }
                    stack.remove(stack.size() - 1);
                    level--;
                }
                action = "POP(" + n + ")";
            }
            case ASSERT -> action = registerAssertion(cmd, diag);
            case CHECK_SAT -> {
                action = "CHECK-SAT";
                makeCheckpoint(cmd, false);
            }
            case CHECK_SAT_ASSUMING -> {
                action = "CHECK-SAT-ASSUMING";
                makeCheckpoint(cmd, true);
            }
            case DECLARE_CONST, DECLARE_FUN, DEFINE_FUN, DECLARE_SORT, DEFINE_SORT -> action = "DECLARE";
            case SET_LOGIC -> action = "SET-LOGIC";
            case SET_OPTION -> action = "SET-OPTION";
            case GET_MODEL -> action = "GET-MODEL";
            case GET_UNSAT_CORE -> action = "GET-UNSAT-CORE";
            case RESET -> {
                stack.clear();
                stack.add(new ArrayList<>());
                level = 0;
                action = "RESET";
            }
            default -> action = cmd.kind().name();
        }
        String id = "scope:" + transcriptId + ":c" + cmd.ordinal();
        events.add(new ScopeEvent(id, cmd.ordinal(), cmd.head(), before, level, action,
                diag, activeLabelNames()));
    }

    private String registerAssertion(SmtCommand cmd, List<String> diag) {
        Sexpr term = cmd.args().get(0);
        List<NamedLabel> found = new ArrayList<>();
        collectNamed(term, found);
        for (NamedLabel nl : found) {
            String id = "bind:" + transcriptId + ":c" + cmd.ordinal() + ":b" + bindingSeq++;
            Binding b = new Binding(id, nl.label, "cmd:" + transcriptId + ":c" + cmd.ordinal(),
                    cmd.ordinal(), level, nl.term);
            if (findCurrentLabel(nl.label) != null) {
                diag.add("label '" + nl.label + "' shadows an earlier binding; "
                        + "the innermost binding resolves and pop restores the prior one");
            }
            stack.get(stack.size() - 1).add(b);
        }
        return "ASSERT" + (found.isEmpty() ? "" : "(" + found.size() + " named)");
    }

    private void makeCheckpoint(SmtCommand cmd, boolean assuming) {
        List<String> lits = new ArrayList<>();
        if (assuming) {
            Sexpr arg = cmd.args().get(0);
            if (arg instanceof Sexpr.List l) {
                for (Sexpr x : l.items()) lits.add(x.raw());
            }
        }
        List<String> resolved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<String> cdiag = new ArrayList<>();
        for (String lit : lits) {
            boolean neg = lit.startsWith("(not ") && lit.endsWith(")");
            String label = neg ? lit.substring(5, lit.length() - 1).trim() : lit;
            Binding b = findCurrentLabel(label);
            if (b != null) {
                resolved.add(label);
            } else {
                unresolved.add(lit);
                cdiag.add("assumption literal '" + lit + "' has no named assertion in scope "
                        + "(one-shot; no global assertion is added)");
            }
        }
        List<List<String>> levelsView = new ArrayList<>();
        for (List<Binding> lb : stack) {
            levelsView.add(lb.stream().map(Binding::label).toList());
        }
        String id = "check:" + transcriptId + ":c" + cmd.ordinal();
        checkpoints.add(new CheckPoint(id, cmd.ordinal(), assuming, lits, levelsView,
                activeBindings(), resolved, unresolved, cdiag));
    }

    private Binding findCurrentLabel(String label) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            for (int j = stack.get(i).size() - 1; j >= 0; j--) {
                Binding b = stack.get(i).get(j);
                if (b.label().equals(label)) return b;
            }
        }
        return null;
    }

    private List<Binding> activeBindings() {
        List<Binding> all = new ArrayList<>();
        for (List<Binding> lb : stack) all.addAll(lb);
        return all;
    }

    private List<String> activeLabelNames() {
        return activeBindings().stream().map(Binding::label).toList();
    }

    private record NamedLabel(String label, Sexpr term) {}

    private void collectNamed(Sexpr term, List<NamedLabel> out) {
        if (term instanceof Sexpr.List l && !l.items().isEmpty()
                && l.items().get(0) instanceof Sexpr.Atom h && "!".equals(h.token())) {
            if (l.items().size() >= 2) {
                Sexpr inner = l.items().get(1);
                String label = null;
                for (int i = 2; i + 1 < l.items().size(); i++) {
                    if (l.items().get(i) instanceof Sexpr.Atom k && ":named".equals(k.token())) {
                        label = Sexpr.token(l.items().get(i + 1));
                    }
                }
                if (label != null) {
                    out.add(new NamedLabel(label, inner));
                    collectNamed(inner, out);
                    return;
                }
            }
        }
        if (term instanceof Sexpr.List l) {
            for (Sexpr c : l.items()) collectNamed(c, out);
        }
    }

    private static int arityOrDefault(SmtCommand cmd, int dflt) {
        if (cmd.args().isEmpty()) return dflt;
        try {
            return Math.max(0, Integer.parseInt(Sexpr.token(cmd.args().get(0))));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    public record Replay(List<ScopeEvent> events, List<CheckPoint> checkpoints, int finalLevel) {}

    /** Unmodifiable label->term view of the bindings active at a checkpoint. */
    public static Map<String, Sexpr> bindingTerms(List<Binding> bindings) {
        Map<String, Sexpr> m = new LinkedHashMap<>();
        for (Binding b : bindings) m.put(b.label(), b.term());
        return Collections.unmodifiableMap(m);
    }
}
