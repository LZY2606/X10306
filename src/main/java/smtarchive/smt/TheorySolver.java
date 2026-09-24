package smtarchive.smt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local decision procedure for the supported fragment:
 * Boolean structure over (in)equalities of linear real arithmetic.
 *
 * Every non-conforming construct is reported as an unsupported-theory atom;
 * integer arithmetic is explicitly reported (the exact engine is real-valued).
 */
public class TheorySolver {

    public enum SatVerdict { SAT, UNSAT, UNKNOWN }

    public record AtomView(String id, Term origin, String description, boolean unsupported,
                           LinearArith.Constraint constraint) {}

    public record FormulaResult(SatVerdict verdict, List<AtomView> atoms, List<String> unsupportedReasons,
                                boolean integerTheoryUsed, boolean atomBudgetExceeded, int atomCount) {}

    private final int atomBudget;

    public TheorySolver(int atomBudget) { this.atomBudget = atomBudget; }

    private final class Context {
        final Map<String, AtomView> atomById = new LinkedHashMap<>();
        final List<String> unsupported = new ArrayList<>();
        boolean integerTheoryUsed;
        boolean budgetExceeded;
        final Map<String, Command.DeclareFun> sorts;

        Context(Map<String, Command.DeclareFun> sorts) { this.sorts = sorts; }

        AtomView intern(Term origin, String desc, LinearArith.Constraint c, boolean unsupp) {
            String id = "a" + atomById.size();
            AtomView v = new AtomView(id, origin, desc, unsupp, c);
            atomById.put(id, v);
            if (unsupp) unsupported.add(desc);
            return v;
        }
    }

    /** Converts a term to a Boolean formula described in the returned atom table. */
    public FormulaResult analyze(Term term, Map<String, Command.DeclareFun> sorts) {
        Context ctx = new Context(sorts);
        Object formula = toFormula(term, ctx);
        int n = ctx.atomById.size();
        if (n > atomBudget) ctx.budgetExceeded = true;
        SatVerdict verdict = ctx.budgetExceeded || !ctx.unsupported.isEmpty() || ctx.integerTheoryUsed
                ? SatVerdict.UNKNOWN
                : decide(formula, List.copyOf(ctx.atomById.values()));
        return new FormulaResult(verdict, List.copyOf(ctx.atomById.values()),
                List.copyOf(ctx.unsupported), ctx.integerTheoryUsed, ctx.budgetExceeded, n);
    }

    // Formula = Boolean (constant), String atomId, Not(f), And(list), Or(list), Implies(a,b), EqBV...
    private sealed interface F permits F.Const, F.Atom, F.Not, F.And, F.Or, F.Imp {}
    private record Const(boolean value) implements F {}
    private record Atom(String id) implements F {}
    private record Not(F f) implements F {}
    private record And(List<F> fs) implements F {}
    private record Or(List<F> fs) implements F {}
    private record Imp(F a, F b) implements F {}

    private F toFormula(Term t, Context ctx) {
        if (t instanceof Term.Annot an) return toFormula(an.inner(), ctx);
        if (t instanceof Term.BoolLit b) return new Const(b.value());
        if (t instanceof Term.Sym s) {
            Command.DeclareFun dcl = ctx.sorts.get(s.name());
            if (dcl != null && dcl.result() == Sort.BOOL) {
                AtomView v = ctx.intern(t, "boolean constant '" + s.name() + "'", null, false);
                return new Atom(v.id());
            }
            AtomView v = ctx.intern(t, "non-boolean symbol '" + s.name() + "' in formula position",
                    null, true);
            return new Atom(v.id());
        }
        if (t instanceof Term.Let lt) {
            // expand lets by substitution (same-name shadow handled locally)
            Term expanded = expandLets(lt, new LinkedHashMap<>());
            return toFormula(expanded, ctx);
        }
        if (t instanceof Term.Unsupported u) {
            AtomView v = ctx.intern(t, u.description(), null, true);
            return new Atom(v.id());
        }
        if (t instanceof Term.Num) {
            AtomView v = ctx.intern(t, "numeric term used as a formula", null, true);
            return new Atom(v.id());
        }
        Term.App app = (Term.App) t;
        switch (app.op()) {
            case "true" -> { return new Const(true); }
            case "false" -> { return new Const(false); }
            case "not" -> { return new F.Not(toFormula(app.args().get(0), ctx)); }
            case "and" -> { return new F.And(app.args().stream().map(a -> toFormula(a, ctx)).toList()); }
            case "or" -> { return new F.Or(app.args().stream().map(a -> toFormula(a, ctx)).toList()); }
            case "=>" -> { return new F.Imp(toFormula(app.args().get(0), ctx), toFormula(app.args().get(1), ctx)); }
            case "xor" -> {
                F a = toFormula(app.args().get(0), ctx), b = toFormula(app.args().get(1), ctx);
                return new F.Or(List.of(new F.And(List.of(a, new F.Not(b))),
                        new F.And(List.of(new F.Not(a), b))));
            }
            case "ite" -> {
                F c = toFormula(app.args().get(0), ctx);
                F a = toFormula(app.args().get(1), ctx), b = toFormula(app.args().get(2), ctx);
                return new F.Or(List.of(new F.And(List.of(c, a)), new F.And(List.of(new F.Not(c), b))));
            }
            case "=", "distinct", "<", "<=", ">", ">=" -> { return atomOfComparison(app, ctx); }
            default -> {
                // a function application returning Bool with args is outside the fragment
                AtomView v = ctx.intern(t, "boolean application '" + app.op() + "' is outside the supported fragment",
                        null, true);
                return new Atom(v.id());
            }
        }
    }

    private F atomOfComparison(Term.App app, Context ctx) {
        if (app.op().equals("=") || app.op().equals("distinct")) {
            Term l = app.args().get(0), r = app.args().get(1);
            Sort ls = sortOf(l, ctx.sorts), rs = sortOf(r, ctx.sorts);
            if (ls == Sort.BOOL && rs == Sort.BOOL) {
                F a = toFormula(l, ctx), b = toFormula(r, ctx);
                F eq = new F.Or(List.of(new F.And(List.of(a, b)), new F.And(List.of(new F.Not(a), new F.Not(b)))));
                return app.op().equals("=") ? eq : new F.Not(eq);
            }
            if (ls.isUnsupported() || rs.isUnsupported()) {
                AtomView v = ctx.intern(app, "equality over unsupported sort " + ls, null, true);
                F f = new Atom(v.id());
                return app.op().equals("=") ? f : new F.Not(f);
            }
        }
        try {
            LinearArith.Constraint c = LinearArith.comparison(app);
            if (app.op().equals("distinct")) {
                // disequality handled as a strict disjunction by the theory layer
                LinearArith.Constraint neq = new LinearArith.Constraint(c.lhs(), "!=", c.rhs());
                AtomView v = ctx.intern(app, "disequality " + neq.diff(), neq, false);
                return new F.Not(new Atom(v.id()));
            }
            markIntVars(c, ctx);
            AtomView v = ctx.intern(app, app.raw(), c, false);
            return new Atom(v.id());
        } catch (LinearArith.NonLinearException ex) {
            AtomView v = ctx.intern(app, "non-linear or unsupported arithmetic: " + ex.getMessage(),
                    null, true);
            return new Atom(v.id());
        }
    }

    private void markIntVars(LinearArith.Constraint c, Context ctx) {
        for (String v : c.lhs().coefs().keySet()) {
            Command.DeclareFun d = ctx.sorts.get(v);
            if (d != null && d.result() == Sort.INT) ctx.integerTheoryUsed = true;
        }
        for (String v : c.rhs().coefs().keySet()) {
            Command.DeclareFun d = ctx.sorts.get(v);
            if (d != null && d.result() == Sort.INT) ctx.integerTheoryUsed = true;
        }
    }

    private Sort sortOf(Term t, Map<String, Command.DeclareFun> sorts) {
        if (t instanceof Term.Num n) return n.isInt() ? Sort.INT : Sort.REAL;
        if (t instanceof Term.BoolLit) return Sort.BOOL;
        if (t instanceof Term.Sym s && sorts.containsKey(s.name())) return sorts.get(s.name()).result();
        if (t instanceof Term.App a) {
            if ("select".equals(a.op())) {
                Sort arr = sortOf(a.args().get(0), sorts);
                if (arr.params().size() == 2) return arr.params().get(1);
            }
        }
        return Sort.UNKNOWN;
    }

    private Term expandLets(Term.Let lt, Map<String, Term> env) {
        Map<String, Term> nested = new LinkedHashMap<>(env);
        for (Term.Let.Binding b : lt.bindings()) nested.put(b.name(), substitute(b.value(), nested));
        return substitute(lt.body(), nested);
    }

    private Term substitute(Term t, Map<String, Term> env) {
        switch (t) {
            case Term.Sym s -> { return env.getOrDefault(s.name(), t); }
            case Term.App a -> {
                List<Term> args = a.args().stream().map(x -> substitute(x, env)).toList();
                return new Term.App(a.op(), args, a.raw());
            }
            case Term.Let l -> { return expandLets(l, env); }
            case Term.Annot an -> { return new Term.Annot(substitute(an.inner(), env), an.attrs()); }
            default -> { return t; }
        }
    }

    private SatVerdict decide(F formula, List<AtomView> atoms) {
        int n = atoms.size();
        if (n > atomBudget) return SatVerdict.UNKNOWN;
        // Enumerate truth assignments to theory atoms; each conjunction is checked by FM.
        long total = 1L << Math.min(n, 62);
        for (long mask = 0; mask < total; mask++) {
            Map<String, Boolean> assignment = new LinkedHashMap<>();
            List<LinearArith.Constraint> active = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                boolean truth = ((mask >> i) & 1L) == 1L;
                assignment.put(atoms.get(i).id(), truth);
                AtomView av = atoms.get(i);
                if (av.constraint() != null) {
                    LinearArith.Constraint c = av.constraint();
                    if (truth) active.add(c);
                    else active.add(negate(c));
                }
            }
            if (Boolean.TRUE.equals(evalFormula(formula, assignment)) && feasible(active)) return SatVerdict.SAT;
        }
        return SatVerdict.UNSAT;
    }

    private LinearArith.Constraint negate(LinearArith.Constraint c) {
        return switch (c.op()) {
            case "<" -> new LinearArith.Constraint(c.lhs(), ">=", c.rhs());
            case "<=" -> new LinearArith.Constraint(c.lhs(), ">", c.rhs());
            case ">" -> new LinearArith.Constraint(c.lhs(), "<=", c.rhs());
            case ">=" -> new LinearArith.Constraint(c.lhs(), "<", c.rhs());
            case "=" -> new LinearArith.Constraint(c.lhs(), "!=", c.rhs());
            default -> new LinearArith.Constraint(c.lhs(), "=", c.rhs());
        };
    }

    private boolean feasible(List<LinearArith.Constraint> active) {
        try { return LinearArith.feasible(active); }
        catch (RuntimeException ex) { return false; }
    }

    private Boolean evalFormula(F f, Map<String, Boolean> m) {
        return switch (f) {
            case Const c -> c.value();
            case Atom a -> m.get(a.id());
            case Not n -> !evalFormula(n.f(), m);
            case And a -> { boolean r = true; for (F x : a.fs()) r &= evalFormula(x, m); yield r; }
            case Or a -> { boolean r = false; for (F x : a.fs()) r |= evalFormula(x, m); yield r; }
            case Imp i -> !evalFormula(i.a(), m) || evalFormula(i.b(), m);
        };
    }
}
