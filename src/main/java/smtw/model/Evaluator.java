package smtw.model;

import smtw.smt.Sexpr;
import smtw.smt.SymbolDecl;
import smtw.smt.SymbolTable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local evaluator for the finite semantic subset. Produces a full {@link EvalNode} tree,
 * so every derived value can be traced to sub-terms. Partial models yield UNDEF nodes;
 * values that cannot be normalised against their unit annotation stay in place as ERROR
 * nodes (the raw value is preserved); constructs outside the subset yield UNSUPPORTED.
 */
public final class Evaluator {

    private final SymbolTable symbols;
    private final Map<String, FuncDef> funcs;
    private final Map<String, MeasureAnn> measures;
    private int unknownCounter = 0;

    public Evaluator(SymbolTable symbols, SolverModel model, Map<String, MeasureAnn> measures) {
        this.symbols = symbols;
        this.funcs = model.byName();
        this.measures = measures == null ? Map.of() : measures;
    }

    public EvalNode evaluate(String rootId, Sexpr term) {
        return eval(term, Map.of(), rootId, new int[]{0});
    }

    private EvalNode eval(Sexpr term, Map<String, Val> env, String parentId, int[] path) {
        String id = parentId + ".n" + path[0]++;
        if (term instanceof Sexpr.Atom a) {
            return evalAtom(a, env, id);
        }
        Sexpr.List l = (Sexpr.List) term;
        String head = l.items().isEmpty() ? "" : (l.items().get(0) instanceof Sexpr.Atom h ? h.token() : "");

        List<EvalNode> kids = new ArrayList<>();
        if ("!".equals(head)) {
            Sexpr inner = l.items().get(1);
            EvalNode n = eval(inner, env, parentId, path);
            return new EvalNode(id, term.raw(), n.value(), statusOf(n), n.unit(), n.canonicalUnit(),
                    n.canonicalValue(), n.coordinateBasis(),
                    n.diagnostic() == null ? null : n.diagnostic(), List.of(n));
        }
        if ("ite".equals(head)) {
            EvalNode c = eval(l.items().get(1), env, parentId, path);
            Val cv = valueOf(c);
            if (cv instanceof Val.BoolV b) {
                EvalNode chosen = eval(l.items().get(b.b() ? 2 : 3), env, parentId, path);
                EvalNode skipped = eval(l.items().get(b.b() ? 3 : 2), env, parentId, path);
                return node(id, term, chosen.value(), statusOf(chosen), chosen,
                        List.of(c, chosen, skipped),
                        "ite selected branch #" + (b.b() ? 1 : 2) + "; other branch evaluated for trace only");
            }
            return unresolved(id, term, "ite condition is not a determined Bool: " + cv, List.of(c));
        }

        for (int i = 1; i < l.items().size(); i++) {
            kids.add(eval(l.items().get(i), env, parentId, path));
        }
        List<Val> vals = new ArrayList<>();
        for (EvalNode k : kids) vals.add(valueOf(k));

        if ("const".equals(head) && l.items().size() == 3
                && l.items().get(1) instanceof Sexpr.Atom sa && sa.token().startsWith("Array")) {
            Val d = vals.get(0);
            return arrayNode(id, term, new Val.ArrV(d, List.of()), kids,
                    "constant array; every un-stored index takes the default value");
        }
        if ("as".equals(head) && l.items().size() == 3
                && l.items().get(1) instanceof Sexpr.Atom cc && "const".equals(cc.token())) {
            Val d = vals.get(0);
            return arrayNode(id, term, new Val.ArrV(d, List.of()), kids,
                    "(as const ...): constant array default value applies to every un-stored index");
        }
        Val r = apply(head, vals, term);
        if (r instanceof Val.UnsupportedV u) {
            return new EvalNode(id, term.raw(), u.toString(), EvalNode.EvalStatus.UNSUPPORTED,
                    null, null, null, null, u.reason(), kids);
        }
        if (r instanceof Val.ErrorV e) {
            return new EvalNode(id, term.raw(), e.toString(), EvalNode.EvalStatus.ERROR,
                    null, null, null, null, e.message(), kids);
        }
        if (r instanceof Val.UndefV u) {
            return new EvalNode(id, term.raw(), u.toString(), EvalNode.EvalStatus.UNDEF,
                    null, null, null, null, u.reason(), kids);
        }
        return finishWithMeasure(id, term, r, kids);
    }

    private EvalNode evalAtom(Sexpr.Atom a, Map<String, Val> env, String id) {
        String t = a.token();
        Val v;
        if ("true".equals(t)) v = Val.BoolV.TRUE;
        else if ("false".equals(t)) v = Val.BoolV.FALSE;
        else if (t.matches("-?\\d+")) v = new Val.IntV(new BigInteger(t));
        else if (isRealLit(t)) v = parseReal(t);
        else if (env.containsKey(t)) v = env.get(t);
        else if (funcs.containsKey(t)) {
            FuncDef fd = funcs.get(t);
            if (fd.isConstant()) {
                v = new FunCallEvaluator(fd, Map.of()).value();
                if (v instanceof Val.UndefV u)
                    return new EvalNode(id, t, u.toString(), EvalNode.EvalStatus.UNDEF, null, null, null, null,
                            u.reason(), List.of());
                if (v instanceof Val.ErrorV e)
                    return new EvalNode(id, t, e.toString(), EvalNode.EvalStatus.ERROR, null, null, null, null,
                            e.message(), List.of());
                return finishWithMeasure(id, a, v, List.of());
            }
            v = new Val.UndefV("symbol '" + t + "' is a function used without arguments (partial model trace)");
        } else if (symbols.symbols().containsKey(t)) {
            v = new Val.UndefV("symbol '" + t + "' is absent from the partial model");
        } else {
            v = new Val.ErrorV("unbound symbol '" + t + "'");
        }
        if (v instanceof Val.UndefV u)
            return new EvalNode(id, t, u.toString(), EvalNode.EvalStatus.UNDEF, null, null, null, null,
                    u.reason(), List.of());
        if (v instanceof Val.ErrorV e)
            return new EvalNode(id, t, e.toString(), EvalNode.EvalStatus.ERROR, null, null, null, null,
                    e.message(), List.of());
        return finishWithMeasure(id, a, v, List.of());
    }

    private EvalNode replaceId(EvalNode n, String id) {
        List<EvalNode> kids = new ArrayList<>();
        for (EvalNode c : n.children()) kids.add(replaceId(c, id + "!" + lastSeg(c.id())));
        return new EvalNode(id, n.term(), n.value(), n.status(), n.unit(), n.canonicalUnit(),
                n.canonicalValue(), n.coordinateBasis(), n.diagnostic(), kids);
    }

    private String lastSeg(String id) {
        int i = Math.max(id.lastIndexOf('.'), id.lastIndexOf('!'));
        return i < 0 ? id : id.substring(i + 1);
    }

    private EvalNode finishWithMeasure(String id, Sexpr term, Val v, List<EvalNode> kids) {
        String rootSym = rootSymbol(term);
        MeasureAnn m = rootSym == null ? null : measures.get(rootSym);
        String unit = m != null && m.hasUnit() ? m.unit() : null;
        String basis = m != null && m.hasBasis() ? m.coordinateBasis() : null;
        String canonUnit = null;
        String canonVal = null;
        String diag = null;
        if (m != null && m.hasUnit()) {
            if (m.convertible() && (v instanceof Val.IntV || v instanceof Val.RealV)) {
                double d = v instanceof Val.IntV iv ? iv.v().doubleValue() : ((Val.RealV) v).asDouble();
                canonUnit = m.canonicalUnit();
                canonVal = trimDouble(d * m.toCanonical()) + " " + canonUnit;
            } else if (!m.convertible()) {
                diag = "raw value kept in place: no conversion from '" + m.unit()
                        + "' to a canonical unit is registered (value is not dropped)";
            }
        }
        EvalNode.EvalStatus st = diag != null ? EvalNode.EvalStatus.ERROR : EvalNode.EvalStatus.OK;
        return new EvalNode(id, term.raw(), v.toString(), st, unit, canonUnit, canonVal, basis, diag, kids);
    }

    private EvalNode arrayNode(String id, Sexpr term, Val.ArrV arr, List<EvalNode> kids, String diag) {
        MeasureAnn m = measures.get(rootSymbol(term));
        String unit = m != null && m.hasUnit() ? m.unit() : null;
        String basis = m != null && m.hasBasis() ? m.coordinateBasis() : null;
        return new EvalNode(id, term.raw(), arr.toString(), EvalNode.EvalStatus.OK,
                unit, null, null, basis, diag, kids);
    }

    private EvalNode unresolved(String id, Sexpr term, String why, List<EvalNode> kids) {
        return new EvalNode(id, term.raw(), "UNDEF", EvalNode.EvalStatus.UNDEF,
                null, null, null, null, why, kids);
    }

    private EvalNode node(String id, Sexpr term, String value, EvalNode.EvalStatus st,
                          EvalNode chosen, List<EvalNode> kids, String diag) {
        MeasureAnn m = measures.get(rootSymbol(term));
        String unit = m != null && m.hasUnit() ? m.unit() : null;
        String basis = m != null && m.hasBasis() ? m.coordinateBasis() : null;
        return new EvalNode(id, term.raw(), value, st, unit, chosen.canonicalUnit(),
                chosen.canonicalValue(), basis, diag, kids);
    }

    private String rootSymbol(Sexpr term) {
        if (term instanceof Sexpr.List l && !l.items().isEmpty()
                && l.items().get(0) instanceof Sexpr.Atom h) {
            String x = h.token();
            return switch (x) {
                case "!", "ite", "and", "or", "not", "=>", "=", "distinct", "<", "<=", ">", ">=",
                        "+", "-", "*", "/", "div", "mod", "select", "store", "as", "const" ->
                        l.items().size() > 1 ? rootSymbol(l.items().get(1)) : null;
                default -> funcs.containsKey(x) || symbols.symbols().containsKey(x) ? x : null;
            };
        }
        if (term instanceof Sexpr.Atom a) {
            return (funcs.containsKey(a.token()) || symbols.symbols().containsKey(a.token()))
                    ? a.token() : null;
        }
        return null;
    }

    private Val apply(String head, List<Val> vals, Sexpr whole) {
        // array operations first
        if ("select".equals(head)) {
            Val a = vals.get(0), i = vals.get(1);
            if (a instanceof Val.ArrV arr) {
                if (!Val.isDetermined(i)) return i;
                return arr.select(i);
            }
            if (a instanceof Val.UndefV || a instanceof Val.ErrorV || a instanceof Val.UnsupportedV) return a;
            return new Val.ErrorV("select applied to non-array value: " + a);
        }
        if ("store".equals(head)) {
            Val a = vals.get(0), i = vals.get(1), x = vals.get(2);
            if (a instanceof Val.ArrV arr) {
                List<Val.ArrV.Store> stores = new ArrayList<>(arr.stores());
                stores.add(new Val.ArrV.Store(i, x));
                return new Val.ArrV(arr.defaultValue(), stores);
            }
            return new Val.ErrorV("store applied to non-array value: " + a);
        }
        for (Val v : vals) {
            if (v instanceof Val.UnsupportedV) return v;
            if (v instanceof Val.UndefV u) return u;
            if (v instanceof Val.ErrorV e) return e;
        }
        return switch (head) {
            case "and" -> Val.BoolV.of(vals.stream().allMatch(v -> ((Val.BoolV) v).b()));
            case "or" -> Val.BoolV.of(vals.stream().anyMatch(v -> ((Val.BoolV) v).b()));
            case "not" -> Val.BoolV.of(!((Val.BoolV) vals.get(0)).b());
            case "xor" -> Val.BoolV.of(((Val.BoolV) vals.get(0)).b() ^ ((Val.BoolV) vals.get(1)).b());
            case "=>" -> Val.BoolV.of(!((Val.BoolV) vals.get(0)).b() || ((Val.BoolV) vals.get(1)).b());
            case "=" -> Val.BoolV.of(Val.valsEqual(vals.get(0), vals.get(1)));
            case "distinct" -> Val.BoolV.of(!Val.valsEqual(vals.get(0), vals.get(1)));
            case "<", "<=", ">", ">=" -> compare(head, vals);
            case "+", "-", "*", "/", "div", "mod", "abs" -> arith(head, vals);
            default -> {
                if (funcs.containsKey(head)) yield applyModelFun(head, vals);
                if (symbols.symbols().containsKey(head))
                    yield new Val.ErrorV("symbol '" + head + "' used as function without a model definition");
                yield new Val.UnsupportedV("symbol '" + head + "' is outside the locally supported theory subset");
            }
        };
    }

    private Val applyModelFun(String name, List<Val> vals) {
        FuncDef fd = funcs.get(name);
        if (fd.params().size() != vals.size()) {
            return new Val.ErrorV("model function '" + name + "' arity mismatch: model has "
                    + fd.params().size() + " params, applied to " + vals.size());
        }
        Map<String, Val> env = new LinkedHashMap<>();
        for (int i = 0; i < vals.size(); i++) env.put(fd.params().get(i).sym(), vals.get(i));
        // body evaluated in this environment
        return new FunCallEvaluator(fd, env).value();
    }

    private Val.BoolV compare(String op, List<Val> vals) {
        int c = compareVal(vals.get(0), vals.get(1));
        return Val.BoolV.of(switch (op) {
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            default -> c >= 0;
        });
    }

    private Val arith(String op, List<Val> vals) {
        boolean real = vals.stream().anyMatch(v -> v instanceof Val.RealV);
        if ("abs".equals(op)) {
            Val x = vals.get(0);
            if (x instanceof Val.IntV i) return new Val.IntV(i.v().abs());
            Val.RealV r = asReal(x);
            return r.p().signum() < 0 ? new Val.RealV(r.p().negate(), r.q()) : r;
        }
        if ("-".equals(op) && vals.size() == 1) {
            return real ? new Val.RealV(asReal(vals.get(0)).p().negate(), asReal(vals.get(0)).q())
                    : new Val.IntV(((Val.IntV) vals.get(0)).v().negate());
        }
        if ("/".equals(op) && real) {
            Val.RealV a = asReal(vals.get(0));
            Val.RealV b = asReal(vals.get(1));
            if (b.p().signum() == 0) return new Val.ErrorV("division by zero in real term");
            return new Val.RealV(a.p().multiply(b.q()), a.q().multiply(b.p()));
        }
        if (("div".equals(op) || "mod".equals(op)) || ("/".equals(op) && !real)) {
            BigInteger a = intOf(vals.get(0));
            BigInteger b = intOf(vals.get(1));
            if (b.signum() == 0) return new Val.ErrorV("integer division by zero");
            if ("mod".equals(op)) return new Val.IntV(a.mod(b.abs()));
            return new Val.IntV(a.divide(b)); // BigInteger.divide truncates toward zero
        }
        Val acc = vals.get(0);
        for (int i = 1; i < vals.size(); i++) {
            boolean r2 = real || vals.get(i) instanceof Val.RealV;
            acc = binArith(op, acc, vals.get(i), r2);
            if (!(acc instanceof Val.IntV || acc instanceof Val.RealV)) return acc;
        }
        return acc;
    }

    private Val binArith(String op, Val x, Val y, boolean real) {
        if (real) {
            Val.RealV a = asReal(x);
            Val.RealV b = asReal(y);
            return switch (op) {
                case "+" -> new Val.RealV(a.p().multiply(b.q()).add(b.p().multiply(a.q())),
                        a.q().multiply(b.q()));
                case "-" -> new Val.RealV(a.p().multiply(b.q()).subtract(b.p().multiply(a.q())),
                        a.q().multiply(b.q()));
                case "*" -> new Val.RealV(a.p().multiply(b.p()), a.q().multiply(b.q()));
                default -> new Val.UnsupportedV("operator '" + op + "' not supported for reals here");
            };
        }
        BigInteger a = intOf(x);
        BigInteger b = intOf(y);
        return new Val.IntV(switch (op) {
            case "+" -> a.add(b);
            case "-" -> a.subtract(b);
            case "*" -> a.multiply(b);
            default -> throw new IllegalStateException("bad int op " + op);
        });
    }

    private Val.RealV asReal(Val v) {
        if (v instanceof Val.RealV r) return r;
        if (v instanceof Val.IntV i) return new Val.RealV(i.v(), BigInteger.ONE);
        throw new IllegalStateException("expected numeric value, got " + v);
    }

    private BigInteger intOf(Val v) {
        if (v instanceof Val.IntV i) return i.v();
        if (v instanceof Val.RealV r && r.p().mod(r.q()).signum() == 0) return r.p().divide(r.q());
        throw new IllegalStateException("expected integer value, got " + v);
    }

    private int compareVal(Val x, Val y) {
        if (x instanceof Val.IntV && y instanceof Val.IntV)
            return ((Val.IntV) x).v().compareTo(((Val.IntV) y).v());
        Val.RealV a = asReal(x);
        Val.RealV b = asReal(y);
        return a.p().multiply(b.q()).compareTo(b.p().multiply(a.q()));
    }

    private boolean isRealLit(String t) {
        return t.matches("-?\\d+\\.\\d+") || t.matches("-?\\d+/\\d+")
                || t.matches("-?\\d+\\.\\d+[eE]-?\\d+");
    }

    private Val parseReal(String t) {
        try {
            if (t.contains("/")) {
                String[] ab = t.split("/");
                return new Val.RealV(new BigInteger(ab[0]), new BigInteger(ab[1]));
            }
            String[] ab = t.split("\\.");
            String frac = ab[1].split("[eE]")[0];
            BigInteger den = BigInteger.TEN.pow(frac.length());
            BigInteger num = new BigInteger(ab[0] + frac);
            if (t.contains("e") || t.contains("E")) {
                int exp = Integer.parseInt(t.split("[eE]")[1]);
                BigInteger ten = BigInteger.TEN.pow(Math.abs(exp));
                if (exp >= 0) num = num.multiply(ten);
                else den = den.multiply(ten);
            }
            return new Val.RealV(num, den);
        } catch (RuntimeException e) {
            return new Val.ErrorV("unparseable real literal: " + t);
        }
    }

    private static String trimDouble(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
        return String.format(java.util.Locale.ROOT, "%.6g", d);
    }

    private static EvalNode.EvalStatus statusOf(EvalNode n) { return n.status(); }
    private static Val valueOf(EvalNode n) {
        return switch (n.status()) {
            case OK -> parseDisplayValue(n.value());
            case UNDEF -> new Val.UndefV(n.diagnostic() == null ? "partial model" : n.diagnostic());
            case ERROR -> new Val.ErrorV(n.diagnostic() == null ? n.value() : n.diagnostic());
            case UNSUPPORTED -> new Val.UnsupportedV(n.diagnostic() == null ? n.value() : n.diagnostic());
        };
    }

    private static Val parseDisplayValue(String s) {
        if ("true".equals(s)) return Val.BoolV.TRUE;
        if ("false".equals(s)) return Val.BoolV.FALSE;
        if (s.matches("-?\\d+")) return new Val.IntV(new BigInteger(s));
        if (s.startsWith("(/ ") && s.endsWith(")")) {
            String[] ab = s.substring(3, s.length() - 1).split(" ");
            return new Val.RealV(new BigInteger(ab[0]), new BigInteger(ab[1]));
        }
        return new Val.UndefV("non-primitive value: " + s);
    }

    /** Evaluates a model function body under bound arguments, reusing subset semantics. */
    private final class FunCallEvaluator {
        private final FuncDef fd;
        private final Map<String, Val> env;

        FunCallEvaluator(FuncDef fd, Map<String, Val> env) {
            this.fd = fd;
            this.env = env;
        }

        Val value() { return go(fd.body()); }

        private Val go(Sexpr term) {
            if (term instanceof Sexpr.Atom a) {
                String t = a.token();
                if ("true".equals(t)) return Val.BoolV.TRUE;
                if ("false".equals(t)) return Val.BoolV.FALSE;
                if (t.matches("-?\\d+")) return new Val.IntV(new BigInteger(t));
                if (isRealLit(t)) return parseReal(t);
                if (env.containsKey(t)) return env.get(t);
                if (funcs.containsKey(t) && funcs.get(t).isConstant()) return go(funcs.get(t).body());
                return new Val.UndefV("partial model: '" + t + "' not defined for this call");
            }
            Sexpr.List l = (Sexpr.List) term;
            String head = l.items().get(0) instanceof Sexpr.Atom h ? h.token() : "";
            if ("ite".equals(head)) {
                Val c = go(l.items().get(1));
                if (c instanceof Val.BoolV b) return go(l.items().get(b.b() ? 2 : 3));
                return c;
            }
            List<Val> vs = new ArrayList<>();
            for (int i = 1; i < l.items().size(); i++) vs.add(go(l.items().get(i)));
            return apply(head, vs, term);
        }
    }
}
