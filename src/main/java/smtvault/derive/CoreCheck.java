package smtvault.derive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import smtvault.domain.CmdRecord;
import smtvault.domain.Sexp;
import smtvault.domain.Transcript;

/**
 * Unsat-core verification in the local finite subset.
 *
 * Contract by design (see case narrative): a core is accepted only if its
 * named assertions are jointly UNSAT in the subset. Minimality is NEVER
 * assumed: the solver may hand back a non-minimal core, and the reduction
 * attempts record that fact while preserving the solver's source names.
 *
 * Enumeration is exhaustive over Boolean free symbols with a fixed seed;
 * anything outside the decidable subset yields UNKNOWN, never a forced verdict.
 */
public final class CoreCheck {

    public static final class ReductionAttempt {
        public final String id;
        public final List<String> triedNames;
        public final String outcome; // UNSAT | SAT | UNKNOWN
        public final String witness;
        public final String reason;

        public ReductionAttempt(String id, List<String> triedNames, String outcome,
                                String witness, String reason) {
            this.id = id;
            this.triedNames = triedNames;
            this.outcome = outcome;
            this.witness = witness;
            this.reason = reason;
        }
    }

    public static final class Report {
        public final String id;
        public final int checkCmdSeq;
        public final List<String> solverCoreNames;
        public final List<ScopeReplay.Assertion> coreAssertions;
        public final List<String> missingNames = new ArrayList<>();
        public final List<String> unknownNames = new ArrayList<>();
        public final List<ReductionAttempt> attempts = new ArrayList<>();
        public String verdict; // CONFIRMED_UNSAT | NOT_UNSAT | UNKNOWN
        public boolean minimal = false;
        public boolean nonMinimal = false;
        public String basisNote;

        public Report(String id, int checkCmdSeq, List<String> solverCoreNames,
                      List<ScopeReplay.Assertion> coreAssertions) {
            this.id = id;
            this.checkCmdSeq = checkCmdSeq;
            this.solverCoreNames = solverCoreNames;
            this.coreAssertions = coreAssertions;
        }
    }

    private final long seed;

    public CoreCheck(long seed) {
        this.seed = seed;
    }

    public List<String> extractCoreNames(Transcript t, int checkSeq) {
        for (CmdRecord cmd : t.commands) {
            if (cmd.seq <= checkSeq) {
                continue;
            }
            if ("check-sat".equals(cmd.commandName())
                    || "check-sat-assuming".equals(cmd.commandName())) {
                break;
            }
            if ("get-unsat-core".equals(cmd.commandName()) && cmd.response != null) {
                List<String> names = new ArrayList<>();
                if (cmd.response.isList()) {
                    for (Sexp s : cmd.response.children()) {
                        names.add(s.token());
                    }
                }
                return names;
            }
        }
        return List.of();
    }

    public Report verify(Transcript t, ScopeReplay.CheckPoint checkpoint) {
        List<String> solverNames = extractCoreNames(t, checkpoint.cmdSeq);
        Map<String, ScopeReplay.Assertion> byName = new LinkedHashMap<>();
        for (ScopeReplay.Assertion a : checkpoint.activeAssertions) {
            if (a.name != null) {
                byName.put(a.name, a);
            }
        }
        List<ScopeReplay.Assertion> chosen = new ArrayList<>();
        Report report = new Report("core@check" + checkpoint.cmdSeq, checkpoint.cmdSeq,
                new ArrayList<>(solverNames), chosen);
        for (String name : solverNames) {
            ScopeReplay.Assertion a = byName.get(name);
            if (a == null) {
                report.missingNames.add(name);
            } else {
                chosen.add(a);
            }
        }

        EnumOutcome joint = enumerate(chosen, checkpoint.assumptions, seed);
        if (joint.kind == OutKind.UNKNOWN) {
            report.verdict = "UNKNOWN";
            report.basisNote = joint.reason;
            chosen.forEach(a -> report.unknownNames.add(a.name));
            return report;
        }
        report.verdict = joint.kind == OutKind.UNSAT ? "CONFIRMED_UNSAT" : "NOT_UNSAT";
        report.basisNote = joint.reason;

        // Reduction attempts: drop one named assertion at a time, in solver order.
        // If every single-name removal is still UNSAT, core is definitely non-minimal
        // (a removable element exists). If every removal is SAT, it is minimal
        // within this one-element deletion envelope. Mixed -> not assumed minimal.
        boolean allStillUnsat = chosen.size() > 1;
        boolean allBecomeSat = chosen.size() > 0;
        for (int i = 0; i < chosen.size(); i++) {
            List<ScopeReplay.Assertion> reduced = new ArrayList<>(chosen);
            ScopeReplay.Assertion removed = reduced.remove(i);
            EnumOutcome o = enumerate(reduced, checkpoint.assumptions, seed);
            String outcome = switch (o.kind) {
                case UNSAT -> "UNSAT";
                case SAT -> "SAT";
                case UNKNOWN -> "UNKNOWN";
            };
            report.attempts.add(new ReductionAttempt(
                    "red@check" + checkpoint.cmdSeq + ":drop:" + (removed.name),
                    reduced.stream().map(x -> x.name).toList(),
                    outcome, o.witness, o.reason));
            if (o.kind != OutKind.UNSAT) {
                allStillUnsat = false;
            }
            if (o.kind != OutKind.SAT) {
                allBecomeSat = false;
            }
        }
        report.nonMinimal = allStillUnsat;
        report.minimal = allBecomeSat && report.attempts.stream()
                .allMatch(a -> a.outcome.equals("SAT"));
        return report;
    }

    private enum OutKind { SAT, UNSAT, UNKNOWN }

    private static final class EnumOutcome {
        final OutKind kind;
        final String witness;
        final String reason;

        EnumOutcome(OutKind kind, String witness, String reason) {
            this.kind = kind;
            this.witness = witness;
            this.reason = reason;
        }
    }

    private static EnumOutcome enumerate(List<ScopeReplay.Assertion> assertions,
                                         List<String> positiveAssumptions, long seed) {
        Set<String> boolSymbols = new LinkedHashSet<>();
        for (ScopeReplay.Assertion a : assertions) {
            collectBoolCandidates(a.term, boolSymbols);
        }
        List<String> vars = new ArrayList<>(boolSymbols);
        // Deterministic order influenced by fixed seed (stable across runs).
        java.util.Collections.sort(vars,
                (x, y) -> Long.compare(hashMix(x, seed), hashMix(y, seed)));

        int n = vars.size();
        if (n > 20) {
            return new EnumOutcome(OutKind.UNKNOWN, null,
                    "finite subset bound exceeded: " + n + " free symbols (>20)");
        }
        long total = 1L << n;
        Map<String, Boolean> firstModel = null;
        boolean sawUnknown = false;
        String unknownReason = null;
        for (long bits = 0; bits < total; bits++) {
            Map<String, Boolean> env = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                env.put(vars.get(i), ((bits >> i) & 1L) == 1L);
            }
            for (String p : positiveAssumptions) {
                env.put(p, true);
            }
            boolean allSat = true;
            for (ScopeReplay.Assertion a : assertions) {
                EvalTree tree = Propositions.evaluate(a.term, env, seed);
                if (tree.status == EvalTree.Status.UNKNOWN
                        || tree.status == EvalTree.Status.ERROR) {
                    sawUnknown = true;
                    unknownReason = tree.reason;
                    allSat = false;
                    break;
                }
                if (tree.status == EvalTree.Status.VIOLATED) {
                    allSat = false;
                    break;
                }
            }
            if (allSat) {
                firstModel = env;
                break;
            }
        }
        if (firstModel != null) {
            return new EnumOutcome(OutKind.SAT, firstModel.toString(),
                    "satisfying Boolean assignment found by exhaustive enumeration");
        }
        if (sawUnknown) {
            return new EnumOutcome(OutKind.UNKNOWN, null,
                    "cannot decide inside local subset: " + unknownReason);
        }
        return new EnumOutcome(OutKind.UNSAT, null,
                "all " + total + " Boolean assignments rejected; subset says unsat");
    }

    private static long hashMix(String s, long seed) {
        long h = seed;
        for (int i = 0; i < s.length(); i++) {
            h = h * 1315423911L ^ s.charAt(i);
        }
        return Long.rotateLeft(h, 7) ^ seed;
    }

    private static void collectBoolCandidates(Sexp term, Set<String> out) {
        if (term.isAtom()) {
            String s = term.token();
            if (!"true".equals(s) && !"false".equals(s) && !s.matches("-?\\d+")
                    && !s.matches("-?\\d+\\.\\d+") && !s.startsWith(":")
                    && !s.startsWith("|")) {
                out.add(s);
            }
            return;
        }
        String op = term.head().isAtom() ? term.head().token() : "";
        boolean boolContextOnly = Set.of("not", "and", "or", "xor", "=>", "=", "distinct",
                "ite", "select").contains(op);
        if (!boolContextOnly && !op.isEmpty()) {
            return;
        }
        for (int i = 0; i < term.argc(); i++) {
            collectBoolCandidates(term.arg(i), out);
        }
    }
}
