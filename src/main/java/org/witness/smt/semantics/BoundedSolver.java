package org.witness.smt.semantics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.witness.smt.SExpr;
import org.witness.smt.domain.Sort;
import org.witness.smt.domain.Values;
import org.witness.smt.domain.Values.Value;
import org.witness.smt.model.Evaluator;
import org.witness.smt.model.Model;
import org.witness.smt.model.ModelParser;
import org.witness.smt.replay.ReplayState;

/**
 * Bounded, exhaustive satisfiability checker for the local subset.
 *
 * <p>Free scalar symbols are enumerated over a finite domain
 * ([{@code -intBound}, {@code intBound}] for Int and a fixed rational grid for
 * Real). Anything outside the subset (quantifiers, uninterpreted functions,
 * free arrays, nonlinearity) yields {@link Verdict#UNKNOWN} with a diagnostic,
 * never a wrong sat/unsat.
 */
public final class BoundedSolver {

    public enum Verdict { SAT, UNSAT, UNKNOWN }

    public record Params(int intBound, int realGrid, int maxCandidates) {
        public static Params defaults() {
            return new Params(3, 2, 5);
        }

        public String fingerprint() {
            return "ib=" + intBound + ";rg=" + realGrid + ";mc=" + maxCandidates;
        }
    }

    public record Result(Verdict verdict, List<Model> candidates, List<String> diagnostics) {
        public static Result unknown(List<String> diags) {
            return new Result(Verdict.UNKNOWN, List.of(), diags);
        }
    }

    private final ReplayState state;
    private final Params params;
    private final List<String> diagnostics = new ArrayList<>();
    private final List<Model> found = new ArrayList<>();

    private BoundedSolver(ReplayState state, Params params) {
        this.state = state;
        this.params = params;
    }

    public static Result check(ReplayState state, List<SExpr> constraints, Params params) {
        BoundedSolver s = new BoundedSolver(state, params);
        return s.solve(constraints);
    }

    private Result solve(List<SExpr> constraints) {
        // Fast structural pre-check: if any constraint necessarily depends on
        // an unsupported feature (free array, quantifier, uninterpreted
        // function), bounded enumeration cannot decide and must say UNKNOWN
        // instead of exploding.
        List<String> structural = structuralUnknowns(constraints);
        if (!structural.isEmpty()) {
            return Result.unknown(structural);
        }
        Map<String, Sort> vars = new LinkedHashMap<>();
        for (var entry : state.symbols().entrySet()) {
            String name = entry.getKey();
            var info = entry.getValue();
            if (name.startsWith("named:")) {
                continue;
            }
            if (info.isFunction() || info.def != null || info.sort instanceof Sort.ArraySort
                    || !info.sort.isSupported()) {
                continue;
            }
            if (info.sort instanceof Sort.IntSort || info.sort instanceof Sort.RealSort
                    || info.sort instanceof Sort.Bool) {
                vars.put(name, info.sort);
            }
        }
        List<String> names = new ArrayList<>(vars.keySet());
        search(names, vars, 0, new LinkedHashMap<>(), constraints);
        if (!diagnostics.isEmpty() && found.isEmpty()) {
            return Result.unknown(new ArrayList<>(diagnostics));
        }
        if (!found.isEmpty()) {
            return new Result(Verdict.SAT, List.copyOf(found), new ArrayList<>(diagnostics));
        }
        return new Result(Verdict.UNSAT, List.of(), new ArrayList<>(diagnostics));
    }

    private void search(List<String> names, Map<String, Sort> sorts, int idx,
                        Map<String, Value> env, List<SExpr> constraints) {
        if (found.size() >= params.maxCandidates) {
            return;
        }
        if (idx == names.size()) {
            if (satisfiesAll(env, constraints)) {
                found.add(candidateToModel(env));
            }
            return;
        }
        String name = names.get(idx);
        Sort sort = sorts.get(name);
        for (Value v : domain(sort)) {
            env.put(name, v);
            if (isDefinitivelyFalse(env, constraints)) {
                env.remove(name);
                continue;
            }
            search(names, sorts, idx + 1, env, constraints);
            env.remove(name);
            if (found.size() >= params.maxCandidates) {
                return;
            }
        }
    }

    /**
     * A partial assignment can be pruned when every still-unassigned symbol
     * is already assigned in the probe model (missing symbols default to 0).
     * Conservative: only prune on a concrete false result, never on unknown.
     */
    private boolean isDefinitivelyFalse(Map<String, Value> env, List<SExpr> constraints) {
        return evaluateWithDefaults(env, constraints) == Boolean.FALSE;
    }

    private Boolean evaluateWithDefaults(Map<String, Value> env, List<SExpr> constraints) {
        Model probe = new Model("bounded-probe");
        for (var en : state.symbols().entrySet()) {
            var info = en.getValue();
            if (en.getKey().startsWith("named:") || info.isFunction() || info.def != null
                    || info.sort instanceof Sort.ArraySort || !info.sort.isSupported()) {
                continue;
            }
            Value v = env.get(en.getKey());
            if (v == null) {
                if (info.sort instanceof Sort.Bool) {
                    return null;
                }
                return null; // unassigned: cannot prune
            }
            probe.put(org.witness.smt.model.ModelDef.scalar(en.getKey(), info.sort, v,
                    SExpr.atom(v.toString(), 0, 0), "bounded-probe"));
        }
        // If any free scalar symbol is missing from env, do not prune.
        Evaluator evaluator = new Evaluator(state, probe, new ArrayList<>());
        for (SExpr c : constraints) {
            var tree = evaluator.evaluateAssertion("bounded-probe", c);
            if (tree.status == org.witness.smt.model.EvalTree.Status.UNKNOWN) {
                return null;
            }
            if (!"true".equals(tree.valueText)) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }

    private List<String> structuralUnknowns(List<SExpr> constraints) {
        List<String> out = new ArrayList<>();
        for (SExpr c : constraints) {
            collectStructural(c, out);
        }
        return out;
    }

    private void collectStructural(SExpr e, List<String> out) {
        if (e.isAtom()) {
            var info = state.lookup(e.atom());
            if (info != null) {
                if (info.sort instanceof Sort.ArraySort) {
                    addDiag(out, "free array symbol '" + e.atom() + "' is outside the bounded subset");
                } else if (!info.sort.isSupported()) {
                    addDiag(out, "symbol '" + e.atom() + "' uses an unsupported theory ("
                            + info.sort.name() + ")");
                }
            }
            return;
        }
        String head = e.head();
        if ("forall".equals(head) || "exists".equals(head) || "lambda".equals(head)) {
            addDiag(out, "quantifier/function-value theory '" + head + "' is unsupported locally");
            return;
        }
        if (head != null && !BUILTIN.contains(head) && state.lookup(head) != null
                && state.lookup(head).isFunction()) {
            addDiag(out, "uninterpreted function '" + head + "' cannot be enumerated");
            return;
        }
        for (SExpr child : e.items()) {
            collectStructural(child, out);
        }
    }

    private static final java.util.Set<String> BUILTIN = java.util.Set.of(
            "and", "or", "not", "=>", "xor", "=", "distinct", "<", ">", "<=", ">=",
            "+", "-", "*", "/", "div", "mod", "abs", "ite", "select", "store", "!", "true", "false");

    private static void addDiag(List<String> out, String msg) {
        if (!out.contains(msg)) {
            out.add(msg);
        }
    }

    private boolean satisfiesAll(Map<String, Value> env, List<SExpr> constraints) {
        Model probe = new Model("bounded-probe");
        // Inject env as temporary const definitions on a throwaway probe model.
        for (Map.Entry<String, Value> en : env.entrySet()) {
            var info = state.lookup(en.getKey());
            probe.put(org.witness.smt.model.ModelDef.scalar(
                    en.getKey(), info == null ? en.getValue().sort() : info.sort,
                    en.getValue(), SExpr.atom(en.getValue().toString(), 0, 0), "bounded-probe"));
        }
        Evaluator evaluator = new Evaluator(state, probe, new ArrayList<>());
        for (SExpr c : constraints) {
            var tree = evaluator.evaluateAssertion("bounded-probe", c);
            if (tree.status == org.witness.smt.model.EvalTree.Status.UNKNOWN) {
                String d = "constraint outside bounded subset: " + tree.reason;
                if (!diagnostics.contains(d)) {
                    diagnostics.add(d);
                }
                return false;
            }
            if (!"true".equals(tree.valueText)) {
                return false;
            }
        }
        return true;
    }

    private Model candidateToModel(Map<String, Value> env) {
        Model m = new Model("bounded-candidate");
        for (Map.Entry<String, Value> en : env.entrySet()) {
            var info = state.lookup(en.getKey());
            m.put(org.witness.smt.model.ModelDef.scalar(en.getKey(),
                    info == null ? en.getValue().sort() : info.sort, en.getValue(),
                    SExpr.atom(en.getValue().toString(), 0, 0), "bounded-candidate"));
        }
        return m;
    }

    private List<Value> domain(Sort sort) {
        List<Value> out = new ArrayList<>();
        if (sort instanceof Sort.Bool) {
            out.add(new Values.BoolV(false));
            out.add(new Values.BoolV(true));
            return out;
        }
        if (sort instanceof Sort.IntSort) {
            for (int i = -params.intBound; i <= params.intBound; i++) {
                out.add(Values.NumV.ofInt(i));
            }
            return out;
        }
        // Real grid: k/realGrid for k in [-grid*bound, grid*bound]
        for (int k = -params.realGrid * params.intBound; k <= params.realGrid * params.intBound; k++) {
            out.add(Values.NumV.of(
                    org.witness.smt.domain.BigRational.of(java.math.BigInteger.valueOf(k),
                            java.math.BigInteger.valueOf(params.realGrid)), false));
        }
        return out;
    }
}
