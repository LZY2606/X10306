package smtw.core;

import smtw.model.Val;
import smtw.sat.SatSolver;
import smtw.sat.SearchParams;
import smtw.sat.SearchResult;
import smtw.smt.Sexpr;
import smtw.smt.SymbolTable;
import smtw.scope.Binding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies an unsat core in the local subset:
 *  the core only has to be INCONSISTENT — minimality is never assumed, only tested;
 *  every label keeps its source binding/command; reduction attempts are recorded individually.
 */
public final class CoreVerifier {

    public static final String ALGO_VERSION = "core-reduce/greedy-v1";

    private final SymbolTable symbols;
    private final SearchParams params;

    public CoreVerifier(SymbolTable symbols, SearchParams params) {
        this.symbols = symbols;
        this.params = params;
    }

    public CoreVerification verify(String id, String checkKey, List<String> reported,
                                   List<Binding> activeBindings, List<Sexpr> baseConstraints) {
        Map<String, Binding> byLabel = new LinkedHashMap<>();
        for (Binding b : activeBindings) byLabel.put(b.label(), b);

        List<String> present = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String label : reported) {
            if (!seen.add(label)) continue;
            if (byLabel.containsKey(label)) present.add(label);
            else unresolved.add(label);
        }
        List<LabelSource> sources = new ArrayList<>();
        List<Sexpr> coreTerms = new ArrayList<>();
        for (String label : present) {
            Binding b = byLabel.get(label);
            sources.add(new LabelSource(label, b.id(), b.assertedCommandId(), b.assertOrdinal(),
                    b.term().raw()));
            coreTerms.add(b.term());
        }

        List<String> reasons = new ArrayList<>();
        if (!unresolved.isEmpty()) {
            reasons.add("core contains labels with no active named assertion: " + unresolved);
        }
        SearchResult full = new SatSolver(symbols, params).search(coreTerms);
        CoreVerification.Verdict verdict;
        if (full.verdict() == SearchResult.Verdict.SAT) {
            verdict = CoreVerification.Verdict.INVALID_SAT;
            reasons.add("the reported core has a satisfying model under the local subset, "
                    + "so it does NOT prove unsat");
        } else if (full.verdict() == SearchResult.Verdict.UNSAT) {
            verdict = CoreVerification.Verdict.VALID_UNSAT;
            reasons.add("the reported core is inconsistent under the local subset; "
                    + "consistency (not minimality) is all that is required");
        } else {
            verdict = CoreVerification.Verdict.UNKNOWN;
            reasons.addAll(full.reasons());
        }

        List<CoreCandidate> candidates = new ArrayList<>();
        candidates.add(new CoreCandidate(id + ":cand:reported", "reported",
                present, List.of(), false, ALGO_VERSION, params.fingerprint()));

        if (verdict == CoreVerification.Verdict.VALID_UNSAT) {
            candidates.add(reduce(id, present, byLabel));
        }
        return new CoreVerification(id, checkKey, reported, unresolved, sources, verdict, reasons,
                candidates, ALGO_VERSION, params.fingerprint());
    }

    private CoreCandidate reduce(String id, List<String> labels, Map<String, Binding> byLabel) {
        List<CoreAttempt> attempts = new ArrayList<>();
        Set<String> needed = new LinkedHashSet<>(labels);
        int ordinal = 0;
        for (String candidate : labels) {
            List<String> retained = new ArrayList<>();
            for (String l : needed) if (!l.equals(candidate)) retained.add(l);
            SearchResult r = new SatSolver(symbols, params)
                    .search(retained.stream().map(l -> byLabel.get(l).term()).toList());
            CoreAttempt.Result res = switch (r.verdict()) {
                case SAT -> CoreAttempt.Result.BECAME_SAT;
                case UNSAT -> CoreAttempt.Result.STILL_UNSAT;
                case UNKNOWN -> CoreAttempt.Result.UNKNOWN;
            };
            String aid = id + ":att:" + ordinal;
            attempts.add(new CoreAttempt(aid, ordinal, retained, List.of(candidate),
                    res, r.reasons(), r.assignmentsTried(), ALGO_VERSION, params.fingerprint()));
            ordinal++;
            if (res == CoreAttempt.Result.BECAME_SAT) {
                needed.add(candidate);
            } else if (res == CoreAttempt.Result.STILL_UNSAT) {
                needed.remove(candidate);
            }
        }
        boolean minimal = attempts.stream().allMatch(a -> a.result() == CoreAttempt.Result.BECAME_SAT)
                && needed.size() == labels.size();
        return new CoreCandidate(id + ":cand:reduced", "greedy-reduced",
                List.copyOf(needed), attempts, minimal, ALGO_VERSION, params.fingerprint());
    }
}
