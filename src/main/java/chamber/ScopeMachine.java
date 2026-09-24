package chamber;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SMT-LIB assertion-stack state machine (SMT-LIB 2.6 semantics):
 * push/pop nest scopes; pop discards inner assertions and declarations;
 * a named assertion shadows an identical outer or earlier name; name
 * resolution finds the innermost, most recent assertion; after pop a
 * shadowed outer name becomes visible again; check-sat-assuming literals
 * are temporary and augment exactly one check.
 */
public final class ScopeMachine {
    public record Assertion(String id, String name, SExpr term, int level, String cmdId) {}
    public record Check(String id, int index, String cmdId, List<String> assumptions, int level) {}
    public record ScopeView(int level, List<Assertion> assertions, Map<String, String> declarations) {}

    private final String prefix;
    private final Deque<List<Assertion>> scopes = new ArrayDeque<>();
    private final Deque<Map<String, String>> decls = new ArrayDeque<>();
    private final List<Check> checks = new ArrayList<>();
    private int assertCounter;

    public ScopeMachine(String idPrefix) {
        this.prefix = idPrefix;
        push();
    }

    public void push() {
        scopes.push(new ArrayList<>());
        decls.push(new LinkedHashMap<>());
    }

    /** Returns names whose resolution changes because of this pop (name to restored assertion id). */
    public Map<String, String> pop() {
        if (scopes.size() <= 1) throw new IllegalStateException("pop below base scope");
        Map<String, String> restored = new LinkedHashMap<>();
        for (Assertion a : scopes.peek()) {
            if (a.name() == null) continue;
            Assertion outer = resolveBelowTop(a.name());
            if (outer != null && !restored.containsKey(a.name())) restored.put(a.name(), outer.id());
        }
        scopes.pop();
        decls.pop();
        return restored;
    }

    public int level() {
        return scopes.size() - 1;
    }

    public Assertion addAssert(SExpr term, String name, String cmdId) {
        String id = "%s-asrt-%04d".formatted(prefix, ++assertCounter);
        Assertion a = new Assertion(id, name, term, level(), cmdId);
        scopes.peek().add(a);
        return a;
    }

    public void declare(String name, String sort) {
        decls.peek().put(name, sort);
    }

    public String sortOf(String name) {
        for (Map<String, String> d : decls) {
            String s = d.get(name);
            if (s != null) return s;
        }
        return null;
    }

    public Map<String, String> visibleDecls() {
        Map<String, String> out = new LinkedHashMap<>();
        List<Map<String, String>> ordered = new ArrayList<>();
        decls.iterator().forEachRemaining(ordered::add);
        Collections.reverse(ordered);
        for (Map<String, String> d : ordered) out.putAll(d);
        return out;
    }

    /** Innermost, most recent assertion with this name; null if none. */
    public Assertion resolve(String name) {
        for (List<Assertion> scope : scopes) {
            for (int i = scope.size() - 1; i >= 0; i--) {
                Assertion a = scope.get(i);
                if (name.equals(a.name())) return a;
            }
        }
        return null;
    }

    private Assertion resolveBelowTop(String name) {
        boolean skippedTop = false;
        for (List<Assertion> scope : scopes) {
            if (!skippedTop) {
                skippedTop = true;
                continue;
            }
            for (int i = scope.size() - 1; i >= 0; i--) {
                Assertion a = scope.get(i);
                if (name.equals(a.name())) return a;
            }
        }
        return null;
    }

    /** All active assertions, base scope first, in assertion order. */
    public List<Assertion> active() {
        List<List<Assertion>> ordered = new ArrayList<>();
        scopes.iterator().forEachRemaining(ordered::add);
        Collections.reverse(ordered);
        List<Assertion> out = new ArrayList<>();
        for (List<Assertion> s : ordered) out.addAll(s);
        return out;
    }

    public Check check(List<String> assumptions, String cmdId) {
        Check c = new Check("%s-chk-%04d".formatted(prefix, checks.size() + 1), checks.size(), cmdId,
                List.copyOf(assumptions), level());
        checks.add(c);
        return c;
    }

    public List<Check> checks() {
        return checks;
    }

    /** Scope views from base (level 0) to innermost, for display. */
    public List<ScopeView> scopeViews() {
        List<List<Assertion>> orderedScopes = new ArrayList<>();
        scopes.iterator().forEachRemaining(orderedScopes::add);
        List<Map<String, String>> orderedDecls = new ArrayList<>();
        decls.iterator().forEachRemaining(orderedDecls::add);
        Collections.reverse(orderedScopes);
        Collections.reverse(orderedDecls);
        List<ScopeView> out = new ArrayList<>();
        for (int i = 0; i < orderedScopes.size(); i++) {
            out.add(new ScopeView(i, List.copyOf(orderedScopes.get(i)), Map.copyOf(orderedDecls.get(i))));
        }
        return out;
    }
}
