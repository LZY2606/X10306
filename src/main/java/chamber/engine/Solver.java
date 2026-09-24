package chamber.engine;

import chamber.smt.SExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local finite-domain re-checker. It does not trust solver responses; it re-derives
 *  results inside the supported semantic subset and reports unknown explicitly. */
public final class Solver {

    public enum Status { SAT, UNSAT, UNKNOWN, ERROR }

    public record CoreEntry(String name, String origin, int commandIndex, int level) {}

    public record ReductionAttempt(String candidate, boolean removed, String outcome) {}

    public record CheckOutcome(
            Status status,
            String reason,
            Map<String, Value> model,
            List<CoreEntry> core,
            List<ReductionAttempt> reductionAttempts,
            List<String> assumptionsUsed,
            List<String> diagnostics) {

        public static CheckOutcome unknown(String reason) {
            return new CheckOutcome(Status.UNKNOWN, reason, null, null, List.of(), List.of(), List.of(reason));
        }

        public static CheckOutcome error(String reason) {
            return new CheckOutcome(Status.ERROR, reason, null, null, List.of(), List.of(), List.of(reason));
        }
    }

    private final SolverParams params;

    public Solver(SolverParams params) {
        this.params = params;
    }

    /** A formula set: named entries (assertions or assumptions) plus anonymous bodies. */
    public record FormulaSet(Map<String, ScopeStack.NamedAssertion> named,
                             List<SExpr> anonymous,
                             List<ScopeStack.NamedAssertion> assumptions) {}

    public CheckOutcome check(ScopeStack scope, List<SExpr> assumptionLits,
                              Map<String, SExpr> declarations, boolean unsupportedTheory) {
        if (unsupportedTheory) {
            return CheckOutcome.unknown(
                    "unsupported theory: session declares sorts/logic outside the local finite semantic subset");
        }
        List<ScopeStack.NamedAssertion> assumptions = new ArrayList<>();
        List<String> assumptionNames = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        for (SExpr lit : assumptionLits) {
            boolean negated = false;
            SExpr nameExpr = lit;
            if (lit instanceof SExpr.ListExpr ll && ll.items().size() == 2
                    && ll.items().get(0) instanceof SExpr.Atom not && not.value().equals("not")) {
                negated = true;
                nameExpr = ll.items().get(1);
            }
            String name = nameExpr.asAtom();
            ScopeStack.NamedAssertion na = scope.lookup(name);
            if (na == null) {
                return CheckOutcome.error("check-sat-assuming: unknown assumption name " + name);
            }
            SExpr body = negated ? SExpr.list(SExpr.atom("not"), na.body()) : na.body();
            // Assumptions live only for this check; they are never pushed onto the scope stack.
            assumptions.add(new ScopeStack.NamedAssertion(name, body, na.level(), na.commandIndex()));
            assumptionNames.add((negated ? "(not " : "") + name + (negated ? ")" : ""));
        }
        FormulaSet fs = new FormulaSet(scope.activeNamed(), scope.activeAnonymous(), assumptions);
        return solve(fs, declarations, assumptionNames, diagnostics, true);
    }

    private CheckOutcome solve(FormulaSet fs, Map<String, SExpr> declarations,
                               List<String> assumptionNames, List<String> diagnostics,
                               boolean reduceCore) {
        List<SExpr> all = new ArrayList<>(fs.anonymous());
        for (ScopeStack.NamedAssertion na : fs.named().values()) all.add(na.body());
        for (ScopeStack.NamedAssertion na : fs.assumptions()) all.add(na.body());

        Map<String, Value> vars = new LinkedHashMap<>();
        for (SExpr f : all) Evaluator.freeVars(f, declarations, vars);

        List<String> intVars = new ArrayList<>();
        List<String> boolVars = new ArrayList<>();
        for (String v : vars.keySet()) {
            SExpr sort = declarations.get(v);
            if (sort instanceof SExpr.Atom a && a.value().equals("Int")) intVars.add(v);
            else if (sort instanceof SExpr.Atom a && a.value().equals("Bool")) boolVars.add(v);
            else {
                return CheckOutcome.unknown("cannot enumerate variable " + v
                        + " of sort " + sort + " in the finite subset");
            }
        }
        long combos = 1;
        for (int i = 0; i < intVars.size(); i++) combos *= params.domain().size();
        for (int i = 0; i < boolVars.size(); i++) combos *= 2;
        if (combos > params.maxCombinations()) {
            return CheckOutcome.unknown("domain explosion: " + combos + " combinations exceed cap "
                    + params.maxCombinations());
        }

        Map<String, Value> found = enumerate(fs, intVars, boolVars, declarations);
        if (found != null) {
            return new CheckOutcome(Status.SAT, null, found, null, List.of(),
                    assumptionNames, diagnostics);
        }
        // UNSAT: build the core with provenance, then record reduction attempts.
        List<CoreEntry> core = new ArrayList<>();
        for (ScopeStack.NamedAssertion na : fs.named().values()) {
            core.add(new CoreEntry(na.name(), "assertion", na.commandIndex(), na.level()));
        }
        for (ScopeStack.NamedAssertion na : fs.assumptions()) {
            core.add(new CoreEntry(na.name(), "assumption", na.commandIndex(), na.level()));
        }
        List<ReductionAttempt> attempts = new ArrayList<>();
        if (reduceCore) {
            core = reduce(fs, declarations, core, attempts);
        }
        return new CheckOutcome(Status.UNSAT, null, null, core, attempts,
                assumptionNames, diagnostics);
    }

    /** Greedy core reduction. The result is unsat and keeps provenance, but is NOT
     *  claimed to be minimal — rounds are bounded by SolverParams. */
    private List<CoreEntry> reduce(FormulaSet fs, Map<String, SExpr> declarations,
                                   List<CoreEntry> core, List<ReductionAttempt> attempts) {
        List<CoreEntry> current = new ArrayList<>(core);
        int rounds = 0;
        boolean changed = true;
        while (changed && rounds < params.maxReductionRounds()) {
            changed = false;
            rounds++;
            for (int i = 0; i < current.size(); i++) {
                CoreEntry entry = current.get(i);
                List<CoreEntry> trial = new ArrayList<>(current);
                trial.remove(i);
                boolean stillUnsat = isUnsat(fs, declarations, trial);
                attempts.add(new ReductionAttempt(entry.name(), stillUnsat,
                        stillUnsat ? "unsat without it — removed" : "sat without it — kept"));
                if (stillUnsat) {
                    current = trial;
                    i--;
                    changed = true;
                }
            }
        }
        return current;
    }

    private boolean isUnsat(FormulaSet fs, Map<String, SExpr> declarations, List<CoreEntry> core) {
        List<SExpr> formulas = new ArrayList<>(fs.anonymous());
        for (CoreEntry e : core) {
            ScopeStack.NamedAssertion na = switch (e.origin()) {
                case "assertion" -> fs.named().get(e.name());
                default -> fs.assumptions().stream()
                        .filter(a -> a.name().equals(e.name())).findFirst().orElse(null);
            };
            if (na != null) formulas.add(na.body());
        }
        Map<String, Value> vars = new LinkedHashMap<>();
        for (SExpr f : formulas) Evaluator.freeVars(f, declarations, vars);
        List<String> intVars = new ArrayList<>();
        List<String> boolVars = new ArrayList<>();
        for (String v : vars.keySet()) {
            SExpr sort = declarations.get(v);
            if (sort instanceof SExpr.Atom a && a.value().equals("Bool")) boolVars.add(v);
            else intVars.add(v);
        }
        return enumerate(formulas, intVars, boolVars, declarations) == null;
    }

    private Map<String, Value> enumerate(FormulaSet fs, List<String> intVars, List<String> boolVars,
                                         Map<String, SExpr> declarations) {
        List<SExpr> all = new ArrayList<>(fs.anonymous());
        for (ScopeStack.NamedAssertion na : fs.named().values()) all.add(na.body());
        for (ScopeStack.NamedAssertion na : fs.assumptions()) all.add(na.body());
        return enumerate(all, intVars, boolVars, declarations);
    }

    /** Deterministic enumeration (fixed order, fixed seed-independent). Returns a model or null. */
    private Map<String, Value> enumerate(List<SExpr> formulas, List<String> intVars,
                                         List<String> boolVars, Map<String, SExpr> declarations) {
        Map<String, Value> assignment = new LinkedHashMap<>();
        return search(formulas, intVars, boolVars, 0, 0, assignment) ? assignment : null;
    }

    private boolean search(List<SExpr> formulas, List<String> intVars, List<String> boolVars,
                           int vi, int bi, Map<String, Value> assignment) {
        if (vi == intVars.size() && bi == boolVars.size()) {
            Evaluator ev = new Evaluator(assignment);
            for (SExpr f : formulas) {
                Value v = ev.eval(f).value();
                if (v instanceof Value.B b && b.v()) continue;
                if (v.isUnknown()) return false; // unknown cannot witness SAT here
                return false;
            }
            return true;
        }
        if (vi < intVars.size()) {
            String var = intVars.get(vi);
            for (long d : params.domain()) {
                assignment.put(var, new Value.I(d));
                if (search(formulas, intVars, boolVars, vi + 1, bi, assignment)) return true;
            }
            assignment.remove(var);
            return false;
        }
        String var = boolVars.get(bi);
        for (boolean b : new boolean[]{false, true}) {
            assignment.put(var, new Value.B(b));
            if (search(formulas, intVars, boolVars, vi, bi + 1, assignment)) return true;
        }
        assignment.remove(var);
        return false;
    }
}
