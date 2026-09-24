package smtroom.core;

import smtroom.sexpr.SExpr;
import smtroom.smt.Command;
import smtroom.smt.Script;
import smtroom.smt.Transcript;
import java.math.BigInteger;
import java.util.*;

/**
 * Re-verifies a session transcript inside the local finite semantic subset.
 * Rules encoded here:
 *  - assumptions of check-sat-assuming apply to exactly that one check;
 *  - pop restores name scope per spec;
 *  - model re-check flags unsupported theories explicitly;
 *  - an unsat core must only be unsatisfiable (bounded check) with provenance
 *    preserved; minimality is never assumed.
 */
public final class Verifier {

    public static final String ALGORITHM = "finite-subset-recheck";
    public static final String ALGO_VERSION = "1.0.0";
    public static final int BOUND = 2;          // Int domain [-2,2]
    public static final int MAX_COMBOS = 200_000;

    public record Diagnostic(String severity, String message) {}

    public record AssertionEval(String assertionId, String name, String status, String detail, EvalNode tree) {}

    public record ModelCheck(List<AssertionEval> evals, List<String> unsupportedTheories,
                             List<String> unknownSymbols, String verdict) {}

    public record CoreMember(String name, String assertionId, boolean resolved, String status) {}

    public record ReductionAttempt(String removedName, String outcome, String witness) {}

    public record CoreCheck(List<CoreMember> members, boolean allResolved, String verdict,
                            String witness, List<ReductionAttempt> attempts, boolean minimalityClaimed) {}

    public record CheckVerdict(int commandIndex, String kind, String solverResponse,
                               List<String> activeAssertionIds, List<String> assumptions,
                               ModelCheck modelCheck, CoreCheck coreCheck) {}

    public record SessionVerdict(String logic, List<CheckVerdict> checks, List<Diagnostic> diagnostics,
                                 List<ScopeStack.LevelView> finalScope) {}

    public SessionVerdict verify(Script script, Transcript transcript) {
        List<CheckVerdict> checks = new ArrayList<>();
        List<Diagnostic> diags = new ArrayList<>();
        ScopeStack stack = new ScopeStack();
        Transcript.Response lastModel = null;
        Transcript.Response lastCore = null;

        for (Command c : script.commands()) {
            switch (c.kind()) {
                case CHECK_SAT, CHECK_SAT_ASSUMING -> {
                    List<String> assumptions = new ArrayList<>();
                    if (c.kind() == Command.Kind.CHECK_SAT_ASSUMING && c.expr().size() >= 2) {
                        for (SExpr lit : c.expr().get(1).items()) assumptions.add(SExpr.print(lit));
                    }
                    Transcript.Response resp = transcript.forCommand(c.index());
                    String solverSays = resp != null ? SExpr.print(resp.parsed()) : "missing";
                    List<String> activeIds = new ArrayList<>();
                    for (ScopeStack.AssertionRec a : stack.activeAssertions()) activeIds.add(a.id());
                    checks.add(new CheckVerdict(c.index(),
                            c.kind() == Command.Kind.CHECK_SAT_ASSUMING ? "check-sat-assuming" : "check-sat",
                            solverSays, activeIds, assumptions, null, null));
                    // assumptions apply only to this check; nothing is pushed onto the stack
                }
                case GET_MODEL -> lastModel = transcript.forCommand(c.index());
                case GET_UNSAT_CORE -> lastCore = transcript.forCommand(c.index());
                default -> ScopeStack.apply(stack, c);
            }
        }

        // Attach model re-check to the most recent sat check and core check to the most recent unsat check.
        if (lastModel != null) attachModel(script, checks, lastModel, diags);
        if (lastCore != null) attachCore(script, checks, lastCore, diags);
        return new SessionVerdict(script.logic(), checks, diags, stack.view());
    }

    private void attachModel(Script script, List<CheckVerdict> checks, Transcript.Response modelResp,
                             List<Diagnostic> diags) {
        // find last sat check before the get-model command
        CheckVerdict target = null;
        for (CheckVerdict cv : checks) {
            if (cv.commandIndex() < modelResp.commandIndex() && cv.solverResponse().equals("sat")) target = cv;
        }
        if (target == null) { diags.add(new Diagnostic("warn", "get-model without preceding sat check")); return; }

        ScopeStack stack = ScopeStack.replay(script, target.commandIndex());
        Evaluator.Env env = buildModelEnv(modelResp.parsed(), stack, diags);
        Evaluator ev = new Evaluator();
        List<AssertionEval> evals = new ArrayList<>();
        Set<String> unsupported = new TreeSet<>();
        Set<String> unknowns = new TreeSet<>();

        List<ScopeStack.AssertionRec> active = new ArrayList<>(stack.activeAssertions());
        // temporary assumptions of that check also must hold under the model
        for (String lit : assumptionLits(script, target)) {
            SExpr t = desugarAssumption(lit, stack);
            if (t != null) active.add(new ScopeStack.AssertionRec("assume:" + lit, null, t, lit, target.commandIndex(), stack.depth()));
        }
        for (ScopeStack.AssertionRec a : active) {
            Evaluator.Out out = ev.eval(a.term(), env);
            collect(out.node(), unsupported, unknowns);
            String status = switch (out.value()) {
                case Value.VBool b -> b.b() ? "TRUE" : "FALSE";
                case Value.VUnknown u -> "UNKNOWN";
                case Value.VUnsupported u -> "UNSUPPORTED";
                default -> "NON-BOOL";
            };
            evals.add(new AssertionEval(a.id(), a.name(), status, Value.show(out.value()), out.node()));
        }
        String verdict = evals.stream().anyMatch(e -> e.status().equals("FALSE")) ? "MODEL-REFUTED"
                : evals.stream().anyMatch(e -> e.status().equals("UNSUPPORTED")) ? "MODEL-UNSUPPORTED-PARTS"
                : evals.stream().anyMatch(e -> e.status().equals("UNKNOWN")) ? "MODEL-PARTIAL"
                : "MODEL-CONFIRMED";
        ModelCheck mc = new ModelCheck(evals, List.copyOf(unsupported), List.copyOf(unknowns), verdict);
        replace(checks, target, mc, null);
    }

    private void attachCore(Script script, List<CheckVerdict> checks, Transcript.Response coreResp,
                            List<Diagnostic> diags) {
        CheckVerdict target = null;
        for (CheckVerdict cv : checks) {
            if (cv.commandIndex() < coreResp.commandIndex() && cv.solverResponse().equals("unsat")) target = cv;
        }
        if (target == null) { diags.add(new Diagnostic("warn", "get-unsat-core without preceding unsat check")); return; }

        ScopeStack stack = ScopeStack.replay(script, target.commandIndex());
        List<String> names = new ArrayList<>();
        if (coreResp.parsed() instanceof SExpr.ListExpr l) {
            for (SExpr s : l.items()) names.add(SExpr.print(s));
        } else names.add(SExpr.print(coreResp.parsed()));

        List<CoreMember> members = new ArrayList<>();
        List<ScopeStack.AssertionRec> coreAssertions = new ArrayList<>();
        boolean allResolved = true;
        for (String n : names) {
            ScopeStack.AssertionRec r = stack.resolveName(n);
            if (r == null) {
                members.add(new CoreMember(n, null, false, "unresolved-name"));
                allResolved = false;
            } else {
                members.add(new CoreMember(n, r.id(), true, "resolved"));
                coreAssertions.add(r);
            }
        }

        // temporary assumptions of the target check participate in unsatisfiability
        List<SExpr> conjuncts = new ArrayList<>();
        for (ScopeStack.AssertionRec a : coreAssertions) conjuncts.add(a.term());
        for (String lit : assumptionLits(script, target)) {
            SExpr t = desugarAssumption(lit, stack);
            if (t != null) conjuncts.add(t);
        }

        BoundedResult res = boundedUnsatCheck(conjuncts, stack);
        String verdict = switch (res.status) {
            case "unsat-bounded" -> allResolved ? "CORE-CONSISTENT-BOUNDED" : "CORE-UNRESOLVED-NAMES";
            case "sat-witness" -> "CORE-REFUTED";
            default -> "CORE-UNKNOWN";
        };
        // reduction attempts: is any member removable within the bound? (minimality NOT required)
        List<ReductionAttempt> attempts = new ArrayList<>();
        for (int i = 0; i < coreAssertions.size(); i++) {
            List<SExpr> reduced = new ArrayList<>(conjuncts);
            // remove conjuncts coming from this assertion (position i in coreAssertions maps directly)
            ScopeStack.AssertionRec removed = coreAssertions.get(i);
            reduced.remove(removed.term());
            BoundedResult rr = boundedUnsatCheck(reduced, stack);
            String outcome = switch (rr.status) {
                case "unsat-bounded" -> "removable-within-bound"; // core may be non-minimal; recorded, not an error
                case "sat-witness" -> "needed";
                default -> "unknown";
            };
            attempts.add(new ReductionAttempt(removed.displayName(), outcome, rr.witness));
        }
        CoreCheck cc = new CoreCheck(members, allResolved, verdict, res.witness, attempts, false);
        replace(checks, target, null, cc);
    }

    // ---- bounded satisfiability over the finite subset ----

    record BoundedResult(String status, String witness) {}

    BoundedResult boundedUnsatCheck(List<SExpr> conjuncts, ScopeStack stack) {
        Map<String, ScopeStack.Decl> decls = stack.visibleDecls();
        Set<String> used = new LinkedHashSet<>();
        for (SExpr t : conjuncts) collectSymbols(t, decls.keySet(), used);
        List<String> vars = new ArrayList<>();
        List<List<Value>> domains = new ArrayList<>();
        Evaluator.Env baseEnv = envFromDefinitions(decls);
        for (String v : used) {
            ScopeStack.Decl d = decls.get(v);
            if (d == null || d.definition() != null) continue;
            if (!d.supported()) return new BoundedResult("unsupported", null);
            List<Value> dom = switch (d.sort()) {
                case "Bool" -> List.of(new Value.VBool(false), new Value.VBool(true));
                case "Int", "Real" -> {
                    List<Value> l = new ArrayList<>();
                    for (int i = -BOUND; i <= BOUND; i++) l.add(new Value.VInt(BigInteger.valueOf(i)));
                    yield l;
                }
                default -> null;
            };
            if (dom == null) return new BoundedResult("unsupported", null);
            vars.add(v); domains.add(dom);
        }
        long combos = 1;
        for (List<Value> d : domains) { combos *= d.size(); if (combos > MAX_COMBOS) return new BoundedResult("unsupported", null); }
        Evaluator ev = new Evaluator();
        int[] idx = new int[vars.size()];
        for (long c = 0; c < combos; c++) {
            Evaluator.Env env = baseEnv;
            StringBuilder wit = new StringBuilder();
            for (int i = 0; i < vars.size(); i++) {
                env = env.withConst(vars.get(i), domains.get(i).get(idx[i]));
                wit.append(vars.get(i)).append('=').append(Value.show(domains.get(i).get(idx[i]))).append(' ');
            }
            boolean allTrue = true, unsupported = false;
            for (SExpr t : conjuncts) {
                Value v = ev.eval(t, env).value();
                if (v instanceof Value.VUnsupported) { unsupported = true; break; }
                if (!(v instanceof Value.VBool b) || !b.b()) { allTrue = false; break; }
            }
            if (unsupported) return new BoundedResult("unsupported", null);
            if (allTrue) return new BoundedResult("sat-witness", wit.toString().trim());
            // next combination
            for (int i = idx.length - 1; i >= 0 && c < combos - 1; i--) {
                if (++idx[i] < domains.get(i).size()) break;
                idx[i] = 0;
            }
        }
        return new BoundedResult("unsat-bounded", null);
    }

    private Evaluator.Env envFromDefinitions(Map<String, ScopeStack.Decl> decls) {
        Evaluator.Env env = Evaluator.Env.empty();
        for (ScopeStack.Decl d : decls.values()) {
            env = new Evaluator.Env(env.consts(), env.funs(), union(env.declared(), d.name()));
            if (d.definition() != null) {
                // define-fun: params unknown here; treat 0-ary as const
                env = env.withFun(d.name(), new Evaluator.FunDef(List.of(), d.definition()));
            }
        }
        return env;
    }

    private static Set<String> union(Set<String> s, String x) {
        Set<String> out = new LinkedHashSet<>(s); out.add(x); return out;
    }

    static void collectSymbols(SExpr t, Set<String> declared, Set<String> out) {
        if (t instanceof SExpr.Atom a) {
            if (declared.contains(a.text())) out.add(a.text());
            return;
        }
        for (SExpr c : t.items()) collectSymbols(c, declared, out);
    }

    // ---- model parsing ----

    Evaluator.Env buildModelEnv(SExpr model, ScopeStack stack, List<Diagnostic> diags) {
        Evaluator.Env env = envFromDefinitions(stack.visibleDecls());
        List<SExpr> defs = model instanceof SExpr.ListExpr l
                && !l.items().isEmpty() && l.items().get(0) instanceof SExpr.Atom h
                && (h.text().equals("model") || h.text().equals("definitions"))
                ? l.items().subList(1, l.items().size())
                : model instanceof SExpr.ListExpr l2 ? l2.items() : List.of();
        Evaluator ev = new Evaluator();
        for (SExpr d : defs) {
            if (!(d instanceof SExpr.ListExpr) || !d.head().equals("define-fun") || d.size() < 5) continue;
            String name = d.get(1).atom();
            List<String> params = new ArrayList<>();
            for (SExpr p : d.get(2).items()) params.add(p.get(0).atom());
            SExpr body = d.get(4);
            if (params.isEmpty()) {
                Value v = ev.eval(body, env).value();
                env = env.withConst(name, v);
            } else {
                env = env.withFun(name, new Evaluator.FunDef(params, body));
            }
        }
        return env;
    }

    private List<String> assumptionLits(Script script, CheckVerdict cv) {
        if (!cv.kind().equals("check-sat-assuming")) return List.of();
        for (Command c : script.commands()) {
            if (c.index() == cv.commandIndex() && c.expr().size() >= 2) {
                List<String> out = new ArrayList<>();
                for (SExpr lit : c.expr().get(1).items()) out.add(SExpr.print(lit));
                return out;
            }
        }
        return List.of();
    }

    /** (not name) / name -> the named assertion term, possibly negated. */
    private SExpr desugarAssumption(String lit, ScopeStack stack) {
        boolean neg = false;
        String name = lit;
        if (lit.startsWith("(not ") && lit.endsWith(")")) {
            neg = true;
            name = lit.substring(5, lit.length() - 1).strip();
        }
        ScopeStack.AssertionRec r = stack.resolveName(name);
        if (r == null) return null;
        if (!neg) return r.term();
        return SExpr.list(SExpr.atom("not"), r.term());
    }

    private void collect(EvalNode n, Set<String> unsupported, Set<String> unknowns) {
        if (n.status.equals("UNSUPPORTED")) unsupported.add(n.value);
        if (n.status.equals("UNKNOWN")) unknowns.add(n.value);
        for (EvalNode c : n.children) collect(c, unsupported, unknowns);
    }

    private void replace(List<CheckVerdict> checks, CheckVerdict target, ModelCheck mc, CoreCheck cc) {
        for (int i = 0; i < checks.size(); i++) {
            CheckVerdict cv = checks.get(i);
            if (cv.commandIndex() == target.commandIndex()) {
                checks.set(i, new CheckVerdict(cv.commandIndex(), cv.kind(), cv.solverResponse(),
                        cv.activeAssertionIds(), cv.assumptions(),
                        mc != null ? mc : cv.modelCheck(), cc != null ? cc : cv.coreCheck()));
                return;
            }
        }
    }
}
