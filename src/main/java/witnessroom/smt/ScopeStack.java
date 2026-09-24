package witnessroom.smt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Assertion-scope stack implementing SMT-LIB push/pop semantics:
 *  - nested push/pop frames;
 *  - named assertions may shadow earlier bindings with the same name;
 *  - pop restores the previous binding of a shadowed name;
 *  - check-sat-assuming literals apply to exactly one check and never
 *    enter the scope stack.
 */
public final class ScopeStack {

    public record Binding(String name, String assertionId, SExpr term, int level, String raw, int line) {}

    public record Assertion(String id, String name, SExpr term, int level, String raw, int line, int commandIndex) {}

    private final List<List<Assertion>> frames = new ArrayList<>();
    private final Map<String, Deque<Binding>> named = new HashMap<>();
    private final Map<String, String> declarations = new LinkedHashMap<>();
    private String logic;
    private int assertionCounter = 0;

    public ScopeStack() {
        frames.add(new ArrayList<>());
    }

    public int depth() { return frames.size(); }

    public String logic() { return logic; }
    public void setLogic(String logic) { this.logic = logic; }

    public Map<String, String> declarations() { return declarations; }
    public void declare(String name, String sort) { declarations.put(name, sort); }

    public Assertion addAssertion(String name, SExpr term, String raw, int line, int commandIndex) {
        Assertion a = new Assertion("asrt-" + (++assertionCounter), name, term, frames.size() - 1, raw, line, commandIndex);
        frames.get(frames.size() - 1).add(a);
        if (name != null) {
            named.computeIfAbsent(name, k -> new ArrayDeque<>())
                    .push(new Binding(name, a.id(), term, a.level(), raw, line));
        }
        return a;
    }

    public void push(int n) {
        for (int i = 0; i < n; i++) frames.add(new ArrayList<>());
    }

    /** Returns the bindings whose shadowing ended because of this pop. */
    public List<Binding> pop(int n) {
        List<Binding> restored = new ArrayList<>();
        for (int i = 0; i < n && frames.size() > 1; i++) {
            List<Assertion> frame = frames.remove(frames.size() - 1);
            for (Assertion a : frame) {
                if (a.name() == null) continue;
                Deque<Binding> stack = named.get(a.name());
                if (stack != null && !stack.isEmpty()) {
                    Binding removed = stack.poll();
                    if (stack.isEmpty()) named.remove(a.name());
                    else restored.add(stack.peek());
                }
            }
        }
        return restored;
    }

    /** Resolve a name to the innermost live binding (shadowing aware). */
    public Binding resolve(String name) {
        Deque<Binding> stack = named.get(name);
        return stack == null ? null : stack.peek();
    }

    public List<Assertion> activeAssertions() {
        List<Assertion> out = new ArrayList<>();
        for (List<Assertion> f : frames) out.addAll(f);
        return out;
    }

    /** Snapshot of the stack for display / archival. */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int lvl = 0; lvl < frames.size(); lvl++) {
            for (Assertion a : frames.get(lvl)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", a.id());
                m.put("level", lvl);
                m.put("name", a.name());
                m.put("term", SExpr.render(a.term()));
                m.put("raw", a.raw());
                m.put("line", a.line());
                m.put("shadowedBy", a.name() == null ? null :
                        named.get(a.name()) != null && !named.get(a.name()).peek().assertionId().equals(a.id())
                                ? named.get(a.name()).peek().assertionId() : null);
                out.add(m);
            }
        }
        return out;
    }
}
