package chamber.engine;

import chamber.smt.SExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Assertion scope stack implementing SMT-LIB push/pop semantics.
 *
 *  - Each push opens a new level; pop discards the top levels.
 *  - A named assertion at a deeper level shadows an outer assertion with the same name.
 *  - After pop, the outer name binding is restored exactly as the standard requires.
 *  - Assumptions from check-sat-assuming are never stored here: they apply to one check only.
 */
public final class ScopeStack {

    public record NamedAssertion(String name, SExpr body, int level, int commandIndex) {}

    public static final class Level {
        public final Map<String, NamedAssertion> named = new LinkedHashMap<>();
        public final List<SExpr> anonymous = new ArrayList<>();
    }

    private final List<Level> levels = new ArrayList<>();

    public ScopeStack() {
        levels.add(new Level()); // level 0 always exists
    }

    public int depth() {
        return levels.size();
    }

    public void push(long n) {
        for (long i = 0; i < n; i++) levels.add(new Level());
    }

    public void pop(long n) {
        if (n >= levels.size()) {
            throw new IllegalStateException("pop " + n + " would remove the base level");
        }
        for (long i = 0; i < n; i++) levels.remove(levels.size() - 1);
    }

    /** Asserts a formula at the current level. A name already used at a deeper-or-equal
     *  visible level is shadowed while this level is live. */
    public void assertFormula(String name, SExpr body, int commandIndex) {
        Level top = levels.get(levels.size() - 1);
        if (name == null) {
            top.anonymous.add(body);
        } else {
            top.named.put(name, new NamedAssertion(name, body, levels.size() - 1, commandIndex));
        }
    }

    /** Visible named assertions, outer-to-inner; inner levels shadow outer same-name entries. */
    public Map<String, NamedAssertion> activeNamed() {
        Map<String, NamedAssertion> out = new LinkedHashMap<>();
        for (Level l : levels) out.putAll(l.named);
        return out;
    }

    public List<SExpr> activeAnonymous() {
        List<SExpr> out = new ArrayList<>();
        for (Level l : levels) out.addAll(l.anonymous);
        return out;
    }

    /** Looks up the currently visible binding of a name (for check-sat-assuming). */
    public NamedAssertion lookup(String name) {
        return activeNamed().get(name);
    }

    /** Snapshot of the stack for display: one row per level with its names. */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < levels.size(); i++) {
            Level l = levels.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", i);
            row.put("named", new ArrayList<>(l.named.keySet()));
            row.put("anonymousCount", l.anonymous.size());
            out.add(row);
        }
        return out;
    }
}
