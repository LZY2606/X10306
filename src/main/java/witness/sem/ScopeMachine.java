package witness.sem;

import witness.smtlib.Session;
import witness.smtlib.Session.Cmd;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays the command stream maintaining the assertion scope stack.
 * - push/pop may nest arbitrarily;
 * - a name bound at a deeper level shadows an outer same-named assertion;
 * - pop restores the outer name binding exactly as the standard requires;
 * - check-sat-assuming literals apply to that single check only.
 */
public final class ScopeMachine {

    /** A named or anonymous assertion with full provenance. */
    public record Assertion(String name, String termText, int cmdIndex, int level) {}

    /** A declaration visible in the scope. */
    public record Decl(String name, String sortText, int cmdIndex, int level) {}

    /** One level of the scope stack. */
    public static final class Level {
        public final int depth;
        public final List<Assertion> assertions = new ArrayList<>();
        public final List<Decl> decls = new ArrayList<>();
        Level(int depth) { this.depth = depth; }
    }

    /** An assumption literal active for exactly one check. */
    public record Assumption(String literal, String name, boolean negated, Assertion target, String diagnostic) {}

    /** Context of a single check command. */
    public record CheckCtx(int checkOrdinal, Cmd cmd, List<Assertion> activeAssertions,
                           List<Assumption> assumptions, List<Level> stackSnapshot,
                           Map<String, Decl> visibleDecls) {}

    private final Session session;
    private final List<Level> stack = new ArrayList<>();
    private final List<CheckCtx> checks = new ArrayList<>();
    /** name -> binding stack (outermost first); pop of a level drops its bindings. */
    private final Map<String, Deque<Assertion>> nameBindings = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();

    public ScopeMachine(Session session) {
        this.session = session;
        stack.add(new Level(0));
        replay();
    }

    public List<CheckCtx> checks() { return checks; }
    public List<String> diagnostics() { return diagnostics; }
    public List<Level> stackNow() { return List.copyOf(stack); }

    private void replay() {
        int ordinal = 0;
        for (Cmd cmd : session.commands) {
            switch (cmd.kind()) {
                case ASSERT -> {
                    Assertion a = new Assertion(cmd.name(), cmd.term() == null ? "" : cmd.term().toString(),
                            cmd.index(), stack.size() - 1);
                    top().assertions.add(a);
                    if (cmd.name() != null) {
                        nameBindings.computeIfAbsent(cmd.name(), k -> new ArrayDeque<>()).addLast(a);
                    }
                }
                case DECLARE_CONST, DECLARE_FUN ->
                    top().decls.add(new Decl(cmd.name(), cmd.term() == null ? "" : cmd.term().toString(), cmd.index(), stack.size() - 1));
                case PUSH -> { for (int i = 0; i < cmd.depth(); i++) pushLevel(); }
                case POP -> { for (int i = 0; i < cmd.depth(); i++) popLevel(); }
                case CHECK_SAT -> checks.add(snapshot(ordinal++, cmd, List.of()));
                case CHECK_SAT_ASSUMING -> checks.add(snapshot(ordinal++, cmd, resolveAssumptions(cmd)));
                default -> { /* declarations of logic/options kept in stream, no scope effect */ }
            }
        }
    }

    private Level top() { return stack.get(stack.size() - 1); }

    private void pushLevel() { stack.add(new Level(stack.size())); }

    private void popLevel() {
        if (stack.size() <= 1) {
            diagnostics.add("pop at base level ignored (cmd stream underflow)");
            return;
        }
        Level removed = stack.remove(stack.size() - 1);
        for (Assertion a : removed.assertions) {
            if (a.name() != null) {
                Deque<Assertion> bindings = nameBindings.get(a.name());
                if (bindings != null) {
                    bindings.removeLastOccurrence(a);
                    if (bindings.isEmpty()) nameBindings.remove(a.name());
                }
            }
        }
    }

    /** Resolve a name as of right now (innermost binding wins). */
    public Assertion resolveName(String name) {
        Deque<Assertion> bindings = nameBindings.get(name);
        return bindings == null ? null : bindings.peekLast();
    }

    private List<Assumption> resolveAssumptions(Cmd cmd) {
        List<Assumption> out = new ArrayList<>();
        for (String lit : cmd.assumptions()) {
            boolean negated = lit.startsWith("(not ");
            String name = negated ? lit.substring(5, lit.length() - 1).trim() : lit.trim();
            Assertion target = resolveName(name);
            String diag = target == null ? "assumption name not in scope: " + name : null;
            if (diag != null) diagnostics.add(diag);
            out.add(new Assumption(lit, name, negated, target, diag));
        }
        return out;
    }

    private CheckCtx snapshot(int ordinal, Cmd cmd, List<Assumption> assumptions) {
        List<Assertion> active = new ArrayList<>();
        Map<String, Decl> decls = new LinkedHashMap<>();
        for (Level lvl : stack) {
            active.addAll(lvl.assertions);
            for (Decl d : lvl.decls) decls.put(d.name(), d);
        }
        List<Level> copy = new ArrayList<>();
        for (Level lvl : stack) {
            Level l = new Level(lvl.depth);
            l.assertions.addAll(lvl.assertions);
            l.decls.addAll(lvl.decls);
            copy.add(l);
        }
        return new CheckCtx(ordinal, cmd, List.copyOf(active), assumptions,
                List.copyOf(copy), Map.copyOf(decls));
    }
}
