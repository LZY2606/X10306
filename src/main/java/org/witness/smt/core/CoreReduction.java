package org.witness.smt.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.witness.smt.SExpr;
import org.witness.smt.domain.Sort;
import org.witness.smt.model.EvalTree;
import org.witness.smt.model.Model;
import org.witness.smt.model.ModelParser;
import org.witness.smt.replay.ReplayState;
import org.witness.smt.replay.Transcript;
import org.witness.smt.semantics.BoundedSolver;
import org.witness.smt.semantics.BoundedSolver.Params;

/**
 * Verifies solver testimony in the local bounded subset.
 *
 * <p>Rules:
 * <ul>
 *   <li>An assumption label is in force only for its single check; it never
 *       enters the scope stack. Pop restores name scope by construction.</li>
 *   <li>Model replay flags unsupported theories explicitly; unknown nodes are
 *       recorded, not turned into pass/fail.</li>
 *   <li>A core only has to be unsat and keep its named source. Minimality is
 *       never assumed: non-minimal cores are accepted but the removal tests
 *       expose every removable member. When bounded search cannot decide a
 *       removal, both possibilities are kept as legal candidates.</li>
 * </ul>
 */
public final class CoreReduction {

    public enum Status { VERIFIED, REJECTED, UNKNOWN }

    public record AssertionEval(String assertionId, String raw, boolean active,
                                EvalTree tree, String value) {
    }

    public record ModelCheck(Status status, boolean allSatisfied, boolean hasUnknown,
                             List<AssertionEval> evals, List<String> unsupported,
                             List<String> missingSymbols, List<String> diagnostics) {
    }

    public record RemovalAttempt(String label, String sourceAssertionId,
                                 Status status, String result, String reason) {
    }

    public record CoreCheck(Status status, boolean unsatBySubset, boolean sourcesPreserved,
                            boolean minimal, List<String> labels, List<String> unknownLabels,
                            List<RemovalAttempt> attempts, List<String> diagnostics) {
    }

    private final ReplayState state;
    private final Transcript transcript;

    public CoreReduction(ReplayState state, Transcript transcript) {
        this.state = state;
        this.transcript = transcript;
    }

    public ModelCheck verifyModel(ReplayState.CheckPoint cp, Params params) {
        int respIndex = findEntryIndex(cp.modelEntryId);
        SExpr payload = transcript.entries().get(respIndex).payload;
        ModelParser parser = new ModelParser();
        Model model = parser.parse(payload, cp.modelEntryId);

        List<String> unsupported = new ArrayList<>(state.unsupportedTheories());
        List<String> missing = new ArrayList<>();
        List<AssertionEval> evals = new ArrayList<>();
        boolean anyUnknown = false;

        for (ReplayState.Assertion a : state.activeAssertions()) {
            List<String> marks = new ArrayList<>();
            var evaluator = new org.witness.smt.model.Evaluator(state, model, marks);
            EvalTree tree = evaluator.evaluateAssertion(a.entryId, a.expr);
            for (String m : marks) {
                if (!unsupported.contains(m)) {
                    unsupported.add(m);
                }
            }
            if (tree.status == EvalTree.Status.UNKNOWN || hasUnknownChild(tree)) {
                anyUnknown = true;
            }
            collectMissing(a.expr, model, missing);
            evals.add(new AssertionEval(a.entryId, a.raw, true, tree, tree.valueText));
        }

        boolean allSat = evals.stream().allMatch(e -> "true".equals(e.value));
        List<String> diags = new ArrayList<>(parser.diagnostics());
        Status status;
        if (anyUnknown || !unsupported.isEmpty()) {
            status = Status.UNKNOWN;
        } else {
            status = allSat ? Status.VERIFIED : Status.REJECTED;
        }
        return new ModelCheck(status, allSat, anyUnknown, evals, unsupported,
                distinct(missing), diags);
    }

    public CoreCheck verifyCore(ReplayState.CheckPoint cp, Params params) {
        int respIndex = findEntryIndex(cp.coreEntryId);
        SExpr payload = transcript.entries().get(respIndex).payload;
        List<String> labels = new ArrayList<>();
        for (SExpr s : payload.items()) {
            labels.add(s.atom());
        }
        List<String> diags = new ArrayList<>();
        List<String> unknownLabels = new ArrayList<>();

        boolean sources = true;
        for (String label : labels) {
            ReplayState.Assertion a = state.assertionOfLabel(label);
            if (a == null) {
                sources = false;
                diags.add("core label '" + label + "' does not resolve to an active named assertion");
            }
        }

        List<SExpr> coreConstraints = constraintsFor(labels, cp.assumptions, unknownLabels);
        BoundedSolver.Result coreResult = BoundedSolver.check(state, coreConstraints, params);
        boolean unsat = coreResult.verdict() == BoundedSolver.Verdict.UNSAT;
        if (coreResult.verdict() == BoundedSolver.Verdict.UNKNOWN) {
            diags.add("bounded solver could not decide unsatisfiability of the core");
        }

        List<RemovalAttempt> attempts = new ArrayList<>();
        Set<String> removable = new LinkedHashSet<>();
        boolean minimal = true;
        for (String label : labels) {
            List<String> reduced = new ArrayList<>(labels);
            reduced.remove(label);
            List<String> unk = new ArrayList<>();
            List<SExpr> constraints = constraintsFor(reduced, cp.assumptions, unk);
            BoundedSolver.Result r = BoundedSolver.check(state, constraints, params);
            ReplayState.Assertion a = state.assertionOfLabel(label);
            String src = a == null ? null : a.entryId;
            RemovalAttempt attempt = switch (r.verdict()) {
                case UNSAT -> {
                    removable.add(label);
                    minimal = false;
                    yield new RemovalAttempt(label, src, Status.VERIFIED, "still-unsat",
                            "constraints remain unsat after removing this member (core is non-minimal)");
                }
                case SAT -> new RemovalAttempt(label, src, Status.REJECTED, "becomes-sat",
                        "removing this member admits a satisfying assignment, so it is needed");
                case UNKNOWN -> {
                    minimal = false;
                    unknownLabels.add(label);
                    yield new RemovalAttempt(label, src, Status.UNKNOWN, "undecided",
                            "bounded search cannot decide the removal; both kept/removed remain legal candidates");
                }
            };
            attempts.add(attempt);
        }

        Status status;
        if (!sources) {
            status = Status.REJECTED;
        } else if (coreResult.verdict() == BoundedSolver.Verdict.UNKNOWN) {
            status = Status.UNKNOWN;
        } else if (unsat) {
            status = Status.VERIFIED;
        } else {
            status = Status.REJECTED;
        }
        return new CoreCheck(status, unsat, sources, minimal, labels, unknownLabels, attempts, diags);
    }

    private List<SExpr> constraintsFor(List<String> labels, List<String> assumptions, List<String> unknownLabels) {
        List<SExpr> out = new ArrayList<>();
        for (String label : labels) {
            SExpr t = state.resolveLabel(label);
            if (t == null) {
                unknownLabels.add(label);
                continue;
            }
            out.add(t);
        }
        // Temporary assumptions belong to this single check only.
        for (String label : assumptions) {
            SExpr t = state.resolveLabel(label);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    private void collectMissing(SExpr term, Model model, List<String> missing) {
        if (term.isList()) {
            for (SExpr c : term.items()) {
                collectMissing(c, model, missing);
            }
            return;
        }
        String t = term.atom();
        if (isFreeAtom(t) && state.lookup(t) != null && model.get(t) == null) {
            var info = state.lookup(t);
            if (!info.isFunction() && info.def == null && info.sort.isSupported()
                    && !(info.sort instanceof Sort.ArraySort)) {
                missing.add(t);
            }
        }
    }

    private boolean isFreeAtom(String t) {
        if ("true".equals(t) || "false".equals(t)) {
            return false;
        }
        try {
            new java.math.BigDecimal(t);
            return false;
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private boolean hasUnknownChild(EvalTree tree) {
        if (tree.status == EvalTree.Status.UNKNOWN) {
            return true;
        }
        for (EvalTree c : tree.children) {
            if (hasUnknownChild(c)) {
                return true;
            }
        }
        return false;
    }

    private int findEntryIndex(String entryId) {
        return Integer.parseInt(entryId.substring(1));
    }

    private static List<String> distinct(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }
}
