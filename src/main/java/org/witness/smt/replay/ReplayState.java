package org.witness.smt.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.witness.smt.SExpr;
import org.witness.smt.domain.Sort;

/**
 * Mutable state of the replayed session: scope stack, active (named)
 * assertions, declarations and one-shot assumption labels.
 *
 * <p>Scope semantics in this local subset:
 * <ul>
 *   <li>{@code push}/{@code pop} are nested; pop restores the frame, so
 *       names introduced only in the popped frame disappear and an outer
 *       same-named label becomes visible again (shadowing / restoration).</li>
 *   <li>{@code check-sat-assuming} labels are temporary: they exist only for
 *       that single check and never enter the scope stack.</li>
 * </ul>
 */
public final class ReplayState {

    public static final class SymbolInfo {
        public final String name;
        public final Sort sort;
        public final List<Sort> argSorts;
        public final String introducedAt;
        public final int frameDepth;
        public FunDef def;

        SymbolInfo(String name, Sort sort, List<Sort> argSorts, String introducedAt, int frameDepth) {
            this.name = name;
            this.sort = sort;
            this.argSorts = argSorts;
            this.introducedAt = introducedAt;
            this.frameDepth = frameDepth;
        }

        public boolean isFunction() {
            return argSorts != null;
        }
    }

    public static final class Assertion {
        public final String entryId;
        public final SExpr expr;
        public final String raw;
        public final int frameDepth;
        public final Map<String, SExpr> labels = new LinkedHashMap<>();

        Assertion(String entryId, SExpr expr, String raw, int frameDepth) {
            this.entryId = entryId;
            this.expr = expr;
            this.raw = raw;
            this.frameDepth = frameDepth;
        }
    }

    public static final class CheckPoint {
        public final int sequence;
        public final String commandEntryId;
        public final String responseEntryId;
        public final int depth;
        public final List<String> assumptions;
        public final List<String> activeAssertionIds;
        public Transcript.ResultKind solverVerdict;
        public String modelEntryId;
        public String coreEntryId;

        CheckPoint(int sequence, String commandEntryId, String responseEntryId, int depth,
                   List<String> assumptions, List<String> activeAssertionIds) {
            this.sequence = sequence;
            this.commandEntryId = commandEntryId;
            this.responseEntryId = responseEntryId;
            this.depth = depth;
            this.assumptions = assumptions;
            this.activeAssertionIds = activeAssertionIds;
        }
    }

    private final Map<String, SymbolInfo> symbols = new LinkedHashMap<>();
    private final List<List<Assertion>> frames = new ArrayList<>();
    private final List<CheckPoint> checks = new ArrayList<>();
    private final List<String> unsupportedTheories = new ArrayList<>();
    private String logic = null;
    private int checkCounter = 0;

    public ReplayState() {
        frames.add(new ArrayList<>());
    }

    public int depth() {
        return frames.size() - 1;
    }

    public String logic() {
        return logic;
    }

    public void setLogic(String logic) {
        this.logic = logic;
    }

    public Map<String, SymbolInfo> symbols() {
        return symbols;
    }

    public List<String> unsupportedTheories() {
        return unsupportedTheories;
    }

    public void push() {
        frames.add(new ArrayList<>());
    }

    public void pop(int levels) {
        if (levels > depth()) {
            throw new IllegalStateException("pop underflow: " + levels + " > depth " + depth());
        }
        for (int k = 0; k < levels; k++) {
            List<Assertion> frame = frames.remove(frames.size() - 1);
            for (Assertion a : frame) {
                for (String label : a.labels.keySet()) {
                    // named labels live exactly as long as their frame.
                    symbols.remove("named:" + label);
                }
            }
        }
    }

    /** Declares a const (argSorts empty) or an uninterpreted function. */
    public void declare(String name, List<Sort> argSorts, Sort result, String entryId) {
        symbols.put(name, new SymbolInfo(name, result, argSorts == null ? List.of() : argSorts, entryId, depth()));
    }

    /** Defines a function or constant in the current frame (may shadow outer names in this subset). */
    public void define(FunDef def, String entryId) {
        List<Sort> args = new ArrayList<>();
        for (FunDef.Param p : def.params()) {
            args.add(p.sort());
        }
        SymbolInfo info = new SymbolInfo(def.name(), def.resultSort(), args, entryId, depth());
        info.def = def;
        symbols.put(def.name(), info);
    }

    public SymbolInfo lookup(String name) {
        return symbols.get(name);
    }

    /** Adds an assertion, harvesting {@code (! t :named label)} labels. */
    public Assertion addAssertion(String entryId, SExpr expr, String raw) {
        Assertion a = new Assertion(entryId, expr, raw, depth());
        harvestLabels(expr, a);
        frames.get(frames.size() - 1).add(a);
        for (Map.Entry<String, SExpr> l : a.labels.entrySet()) {
            symbols.put("named:" + l.getKey(),
                    new SymbolInfo(l.getKey(), Sort.BOOL, List.of(), entryId, depth()));
        }
        return a;
    }

    static void harvestLabels(SExpr expr, Assertion a) {
        if (!expr.isList()) {
            return;
        }
        if ("!".equals(expr.head())) {
            SExpr term = expr.arg(0);
            for (int i = 1; i < expr.arity(); i += 2) {
                if (i + 1 < expr.arity()
                        && ":named".equals(expr.arg(i).atom())
                        && expr.arg(i + 1).isAtom()) {
                    a.labels.put(expr.arg(i + 1).atom(), term);
                }
            }
            harvestLabels(term, a);
            return;
        }
        for (SExpr child : expr.items()) {
            harvestLabels(child, a);
        }
    }

    public List<Assertion> activeAssertions() {
        List<Assertion> all = new ArrayList<>();
        for (List<Assertion> frame : frames) {
            all.addAll(frame);
        }
        return all;
    }

    /** Returns the term behind an active named label, innermost frame first. */
    public SExpr resolveLabel(String label) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            for (Assertion a : frames.get(i)) {
                SExpr e = a.labels.get(label);
                if (e != null) {
                    return e;
                }
            }
        }
        return null;
    }

    public Assertion assertionOfLabel(String label) {
        for (int i = frames.size() - 1; i >= 0; i--) {
            for (Assertion a : frames.get(i)) {
                if (a.labels.containsKey(label)) {
                    return a;
                }
            }
        }
        return null;
    }

    public CheckPoint newCheck(String commandEntryId, String responseEntryId, List<String> assumptions) {
        List<String> ids = activeAssertions().stream().map(a -> a.entryId).toList();
        CheckPoint cp = new CheckPoint(checkCounter++, commandEntryId, responseEntryId, depth(),
                List.copyOf(assumptions), ids);
        checks.add(cp);
        return cp;
    }

    public List<CheckPoint> checks() {
        return checks;
    }

    public CheckPoint lastCheck() {
        return checks.isEmpty() ? null : checks.get(checks.size() - 1);
    }

    public void noteUnsupported(String theoryMarker) {
        if (!unsupportedTheories.contains(theoryMarker)) {
            unsupportedTheories.add(theoryMarker);
        }
    }

    public void reset() {
        symbols.clear();
        frames.clear();
        frames.add(new ArrayList<>());
        checks.clear();
        unsupportedTheories.clear();
        logic = null;
        checkCounter = 0;
    }

    public void resetAssertions() {
        frames.clear();
        frames.add(new ArrayList<>());
    }
}
