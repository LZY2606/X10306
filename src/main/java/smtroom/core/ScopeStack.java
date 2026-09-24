package smtroom.core;

import smtroom.sexpr.SExpr;
import smtroom.smt.Command;
import smtroom.smt.Script;
import java.util.*;

/**
 * Replays a script's push/pop structure. Named assertions live in the level
 * where they were asserted; a name asserted deeper shadows an outer same-name
 * assertion, and pop restores the outer binding per SMT-LIB scoping rules.
 */
public final class ScopeStack {

    public record Decl(String name, String sort, SExpr definition, boolean supported, int commandIndex) {}

    public record AssertionRec(String id, String name, SExpr term, String raw, int commandIndex, int level) {
        public String displayName() { return name != null ? name : id; }
    }

    static final class Level {
        final int depth;
        final Map<String, Decl> decls = new LinkedHashMap<>();
        final List<AssertionRec> assertions = new ArrayList<>();
        final Map<String, AssertionRec> namedHere = new LinkedHashMap<>();
        Level(int depth) { this.depth = depth; }
    }

    private final Deque<Level> levels = new ArrayDeque<>();
    private final List<String> log = new ArrayList<>();

    public ScopeStack() { levels.push(new Level(0)); }

    public int depth() { return levels.size() - 1; }

    public List<String> log() { return Collections.unmodifiableList(log); }

    public void push() { levels.push(new Level(levels.size())); }

    public void pop() {
        if (levels.size() <= 1) { log.add("pop at level 0 ignored (nothing to pop)"); return; }
        levels.pop();
    }

    public void declare(String name, String sort, SExpr definition, boolean supported, int commandIndex) {
        levels.peek().decls.put(name, new Decl(name, sort, definition, supported, commandIndex));
    }

    public AssertionRec addAssertion(SExpr term, String raw, int commandIndex) {
        String name = null;
        SExpr t = term;
        if (term instanceof SExpr.ListExpr l && l.items().size() >= 3
                && l.items().get(0) instanceof SExpr.Atom a && a.text().equals("!")) {
            t = l.items().get(1);
            for (int i = 2; i + 1 < l.items().size(); i++) {
                if (l.items().get(i) instanceof SExpr.Atom k && k.text().equals(":named")
                        && l.items().get(i + 1) instanceof SExpr.Atom n) {
                    name = n.text();
                }
            }
        }
        String id = "a" + commandIndex + (name != null ? ":" + name : "");
        AssertionRec rec = new AssertionRec(id, name, t, raw, commandIndex, depth());
        Level lvl = levels.peek();
        lvl.assertions.add(rec);
        if (name != null) lvl.namedHere.put(name, rec);
        return rec;
    }

    /** Resolve a name to the innermost visible named assertion (shadowing aware). */
    public AssertionRec resolveName(String name) {
        for (Level lvl : levels) {
            AssertionRec r = lvl.namedHere.get(name);
            if (r != null) return r;
        }
        return null;
    }

    /** Resolve a declaration, innermost first. */
    public Decl resolveDecl(String name) {
        for (Level lvl : levels) {
            Decl d = lvl.decls.get(name);
            if (d != null) return d;
        }
        return null;
    }

    public Map<String, Decl> visibleDecls() {
        Map<String, Decl> out = new LinkedHashMap<>();
        List<Level> copy = new ArrayList<>();
        levels.descendingIterator().forEachRemaining(copy::add);
        for (Level lvl : copy) out.putAll(lvl.decls); // inner overrides outer
        return out;
    }

    /** All currently active assertions, outer level first (stable order). */
    public List<AssertionRec> activeAssertions() {
        List<AssertionRec> out = new ArrayList<>();
        List<Level> copy = new ArrayList<>();
        levels.descendingIterator().forEachRemaining(copy::add);
        for (Level lvl : copy) out.addAll(lvl.assertions);
        return out;
    }

    /** Snapshot rows for display: per level, assertions with shadowing status. */
    public List<LevelView> view() {
        List<LevelView> out = new ArrayList<>();
        List<Level> copy = new ArrayList<>();
        levels.descendingIterator().forEachRemaining(copy::add);
        for (Level lvl : copy) {
            List<String[]> rows = new ArrayList<>();
            for (AssertionRec r : lvl.assertions) {
                String shadow = "";
                if (r.name() != null) {
                    AssertionRec winner = resolveName(r.name());
                    if (winner != r) shadow = "shadowed-by:" + winner.id();
                }
                rows.add(new String[]{r.id(), r.displayName(), SExpr.print(r.term()), shadow});
            }
            List<String> dl = new ArrayList<>();
            for (Decl d : lvl.decls.values()) dl.add(d.name() + ":" + d.sort() + (d.supported() ? "" : " [unsupported-sort]"));
            out.add(new LevelView(lvl.depth, rows, dl));
        }
        return out;
    }

    public record LevelView(int depth, List<String[]> assertions, List<String> decls) {}

    /** Build the scope stack as it stands immediately before command at index `upto`. */
    public static ScopeStack replay(Script script, int upto) {
        ScopeStack st = new ScopeStack();
        for (Command c : script.commands()) {
            if (c.index() >= upto) break;
            apply(st, c);
        }
        return st;
    }

    public static void apply(ScopeStack st, Command c) {
        SExpr e = c.expr();
        switch (c.kind()) {
            case SET_LOGIC -> st.log.add("logic=" + (e.size() > 1 ? e.get(1).atom() : "?"));
            case DECLARE_CONST -> st.declare(e.get(1).atom(), SExpr.print(e.get(2)), null,
                    Sorts.isSupported(e.get(2)), c.index());
            case DECLARE_FUN -> {
                String name = e.get(1).atom();
                SExpr sortE = e.get(3);
                st.declare(name, SExpr.print(sortE), null, Sorts.isSupported(sortE) && Sorts.allSupported(e.get(2)), c.index());
            }
            case DEFINE_FUN -> {
                String name = e.get(1).atom();
                st.declare(name, SExpr.print(e.get(3)), e.get(4), Sorts.isSupported(e.get(3)), c.index());
            }
            case ASSERT -> st.addAssertion(e.get(1), c.raw(), c.index());
            case PUSH -> st.push();
            case POP -> st.pop();
            default -> { }
        }
    }
}
