package smtarchive.smt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Linear real arithmetic: linear expressions, atom extraction and an exact
 * Fourier-Motzkin feasibility check. Non-linear multiplication is reported,
 * never approximated. Integer variables are reported as an unsupported theory.
 */
public final class LinearArith {
    private LinearArith() {}

    /** c + sum(coef_i * x_i) */
    public record LinearExpr(Rational constant, Map<String, Rational> coefs) {
        public static LinearExpr num(Rational r) { return new LinearExpr(r, new LinkedHashMap<>()); }
        public static LinearExpr zero() { return num(Rational.ZERO); }
    }

    public static final class NonLinearException extends RuntimeException {
        public NonLinearException(String m) { super(m); }
    }

    public static LinearExpr normalize(Term t) {
        switch (t) {
            case Term.Num n -> { return LinearExpr.num(n.value()); }
            case Term.Sym s -> {
                LinearExpr e = LinearExpr.zero();
                e.coefs().put(s.name(), Rational.ONE);
                return e;
            }
            case Term.App a -> {
                return switch (a.op()) {
                    case "+" -> {
                        LinearExpr r = LinearExpr.zero();
                        for (Term x : a.args()) { LinearExpr y = normalize(x); r = add(r, y); }
                        yield r;
                    }
                    case "-" -> {
                        if (a.args().size() == 1) yield neg(normalize(a.args().get(0)));
                        LinearExpr r = normalize(a.args().get(0));
                        for (int i = 1; i < a.args().size(); i++) r = sub(r, normalize(a.args().get(i)));
                        yield r;
                    }
                    case "*" -> {
                        LinearExpr constAcc = LinearExpr.num(Rational.ONE);
                        LinearExpr varPart = null;
                        for (Term x : a.args()) {
                            LinearExpr y = normalize(x);
                            if (y.coefs().isEmpty()) constAcc = mulConst(constAcc, y.constant());
                            else if (varPart == null) varPart = y;
                            else throw new NonLinearException("product of two variables is non-linear");
                        }
                        yield varPart == null ? constAcc : scale(varPart, constAcc.constant());
                    }
                    case "uminus" -> { yield neg(normalize(a.args().get(0))); }
                    default -> throw new NonLinearException("'" + a.op() + "' is not linear arithmetic");
                };
            }
            case Term.Annot an -> { return normalize(an.inner()); }
            case Term.Let lt -> throw new NonLinearException("let must be expanded before normalization");
            case Term.BoolLit b -> throw new NonLinearException("boolean in arithmetic position");
            case Term.Unsupported u -> throw new NonLinearException("unsupported theory construct");
        }
    }

    private static LinearExpr add(LinearExpr a, LinearExpr b) {
        LinearExpr r = new LinearExpr(a.constant().add(b.constant()), new LinkedHashMap<>(a.coefs()));
        b.coefs().forEach((k, v) -> r.coefs().merge(k, v, Rational::add));
        return prune(r);
    }
    private static LinearExpr sub(LinearExpr a, LinearExpr b) { return add(a, neg(b)); }
    private static LinearExpr neg(LinearExpr a) {
        LinearExpr r = new LinearExpr(a.constant().neg(), new LinkedHashMap<>());
        a.coefs().forEach((k, v) -> r.coefs().put(k, v.neg()));
        return r;
    }
    private static LinearExpr scale(LinearExpr a, Rational c) {
        LinearExpr r = new LinearExpr(a.constant().mul(c), new LinkedHashMap<>());
        a.coefs().forEach((k, v) -> r.coefs().put(k, v.mul(c)));
        return prune(r);
    }
    private static LinearExpr prune(LinearExpr e) {
        e.coefs().values().removeIf(Rational::isZero);
        return e;
    }

    /** Strict/non-strict inequality over linear expressions. */
    public record Constraint(LinearExpr lhs, String op, LinearExpr rhs) {
        public LinearExpr diff() { return sub(lhs, rhs); }
    }

    public static Constraint comparison(Term t) {
        if (t instanceof Term.Annot an) return comparison(an.inner());
        if (t instanceof Term.App a && List.of("<", "<=", ">", ">=", "=").contains(a.op())
                && a.args().size() == 2) {
            return new Constraint(normalize(a.args().get(0)), a.op(), normalize(a.args().get(1)));
        }
        throw new NonLinearException("not a linear comparison: " + t);
    }

    /**
     * Feasibility of conjunctions of strict/non-strict linear inequalities and
     * disequalities, via Fourier-Motzkin elimination with branching on disequalities.
     * Purely exact over rationals.
     */
    public static boolean feasible(List<Constraint> constraints) {
        List<Ineq> ineqs = new ArrayList<>();
        List<Constraint> diseqs = new ArrayList<>();
        for (Constraint c : constraints) {
            LinearExpr d = c.diff();
            switch (c.op()) {
                case "<" -> ineqs.add(new Ineq(d, true));   // d < 0
                case "<=" -> ineqs.add(new Ineq(d, false)); // d <= 0
                case ">" -> ineqs.add(new Ineq(neg(d), true));
                case ">=" -> ineqs.add(new Ineq(neg(d), false));
                case "=" -> {
                    ineqs.add(new Ineq(d, false));
                    ineqs.add(new Ineq(neg(d), false));
                }
                case "!=" -> diseqs.add(c);
            }
        }
        return branch(ineqs, diseqs);
    }

    private static boolean branch(List<Ineq> ineqs, List<Constraint> diseqs) {
        if (diseqs.isEmpty()) return fmFeasible(ineqs);
        Constraint head = diseqs.get(0);
        List<Constraint> rest = diseqs.subList(1, diseqs.size());
        LinearExpr d = head.diff();
        List<Ineq> pos = new ArrayList<>(ineqs); pos.add(new Ineq(d, true));
        List<Ineq> neg = new ArrayList<>(ineqs); neg.add(new Ineq(neg(d), true));
        return branch(pos, rest) || branch(neg, rest);
    }

    /** expr <= 0 or expr < 0 */
    private record Ineq(LinearExpr expr, boolean strict) {}

    static boolean fmFeasible(List<Ineq> ineqs) {
        // trivial contradiction / tautology filtering
        List<Ineq> work = new ArrayList<>();
        for (Ineq q : ineqs) {
            if (q.expr().coefs().isEmpty()) {
                Rational c = q.expr().constant();
                if (q.strict()) { if (c.compareTo(Rational.ZERO) >= 0) return false; }
                else if (c.compareTo(Rational.ZERO) > 0) return false;
                continue;
            }
            work.add(q);
        }
        if (work.isEmpty()) return true;

        String var = pickVar(work);
        if (var == null) return true;

        List<Ineq> positive = new ArrayList<>(); // coef > 0: var <= U
        List<Ineq> negative = new ArrayList<>(); // coef < 0: var >= L
        List<Ineq> zero = new ArrayList<>();
        for (Ineq q : work) {
            Rational c = q.expr().coefs().get(var);
            if (c == null) zero.add(q);
            else if (c.compareTo(Rational.ZERO) > 0) positive.add(q);
            else negative.add(q);
        }
        // if var is only bounded one side, it is unbounded that way: drop all rows on it
        if (positive.isEmpty() || negative.isEmpty()) return fmFeasible(zero);

        List<Ineq> next = new ArrayList<>(zero);
        for (Ineq up : positive) {
            for (Ineq lo : negative) {
                // Upper row: c_u x + a <= 0 (c_u > 0) => x <= -a/c_u ; bound term U = -a/c_u
                // Lower row: c_l x + b <= 0 (c_l < 0) => x >= -b/c_l ; bound term L = -b/c_l
                // Combined feasibility: L <= U, i.e. L - U <= 0.
                LinearExpr uBound = boundTerm(up, var);   // -rest/c
                LinearExpr lBound = boundTerm(lo, var);
                LinearExpr bound = sub(lBound, uBound);
                boolean strict = up.strict() || lo.strict();
                if (bound.coefs().isEmpty()) {
                    int cmp = bound.constant().compareTo(Rational.ZERO);
                    if (strict) { if (cmp >= 0) return false; }
                    else if (cmp > 0) return false;
                } else {
                    next.add(new Ineq(bound, strict));
                }
            }
        }
        return fmFeasible(next);
    }

    /** Bound term -rest/c for a row c*var + rest </<= 0. */
    private static LinearExpr boundTerm(Ineq q, String var) {
        Rational c = q.expr().coefs().get(var);
        LinearExpr rest = new LinearExpr(q.expr().constant(), new LinkedHashMap<>(q.expr().coefs()));
        rest.coefs().remove(var);
        return scale(neg(rest), Rational.of(c.denominator(), c.numerator()));
    }

    private static String pickVar(List<Ineq> ineqs) {
        java.util.Optional<String> v = ineqs.stream()
                .flatMap(q -> q.expr().coefs().keySet().stream()).findFirst();
        return v.orElse(null);
    }
}
