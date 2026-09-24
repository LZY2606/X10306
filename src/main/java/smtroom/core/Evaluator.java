package smtroom.core;

import smtroom.sexpr.SExpr;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/**
 * Evaluator for the local finite semantic subset: Bool / Int / Real arithmetic,
 * arrays with default values, let/ite, defined functions. Anything outside the
 * subset yields first-class UNKNOWN (partial model) or UNSUPPORTED (theory).
 */
public final class Evaluator {

    public record FunDef(List<String> params, SExpr body) {}

    public record Env(Map<String, Value> consts, Map<String, FunDef> funs, Set<String> declared) {
        public static Env empty() { return new Env(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashSet<>()); }
        public Env withConst(String n, Value v) {
            Map<String, Value> c = new LinkedHashMap<>(consts); c.put(n, v);
            return new Env(c, funs, declared);
        }
        public Env withFun(String n, FunDef f) {
            Map<String, FunDef> fs = new LinkedHashMap<>(funs); fs.put(n, f);
            return new Env(consts, fs, declared);
        }
    }

    public record Out(Value value, EvalNode node) {}

    public Out eval(SExpr t, Env env) {
        return eval(t, env, 0);
    }

    private Out eval(SExpr t, Env env, int depth) {
        if (depth > 200) return leaf(t, Value.unsupported("recursion-limit"));
        if (t instanceof SExpr.Atom a) return atom(a.text(), env);
        List<SExpr> items = t.items();
        if (items.isEmpty()) return leaf(t, Value.unsupported("empty-list"));
        SExpr head = items.get(0);

        // ((as const (Array I E)) v)
        if (head instanceof SExpr.ListExpr hl && hl.items().size() == 3
                && hl.items().get(0) instanceof SExpr.Atom as && as.text().equals("as")
                && hl.items().get(1) instanceof SExpr.Atom c && c.text().equals("const")) {
            if (items.size() != 2) return leaf(t, Value.unsupported("as-const-arity"));
            Out v = eval(items.get(1), env, depth + 1);
            EvalNode n = node(t, v.value);
            n.children.add(v.node);
            return new Out(new Value.VArray(v.value, Value.arrayEntries()), n);
        }
        if (!(head instanceof SExpr.Atom h)) return leaf(t, Value.unsupported("computed-head"));
        String op = h.text();
        List<SExpr> args = items.subList(1, items.size());

        switch (op) {
            case "not": return unary(t, args, env, depth, vs -> boolOp(vs[0], b -> !b));
            case "and": return naryBool(t, args, env, depth, true);
            case "or": return naryBool(t, args, env, depth, false);
            case "xor": return nary(t, args, env, depth, vs -> {
                boolean r = false;
                for (Value v : vs) { Boolean b = asBool(v); if (b == null) return lift(vs, "xor"); r ^= b; }
                return new Value.VBool(r);
            });
            case "=>": return nary(t, args, env, depth, vs -> {
                for (int i = 0; i < vs.length - 1; i++) {
                    Boolean a = asBool(vs[i]); Boolean b = asBool(vs[i + 1]);
                    if (a == null || b == null) return lift(vs, "=>");
                    if (a && !b) return new Value.VBool(false);
                }
                return new Value.VBool(true);
            });
            case "=": case "distinct": {
                boolean eq = op.equals("=");
                return nary(t, args, env, depth, vs -> {
                    for (int i = 0; i + 1 < vs.length; i++) {
                        Boolean c = valueEq(vs[i], vs[i + 1]);
                        if (c == null) return lift(vs, op);
                        if (eq && !c) return new Value.VBool(false);
                        if (!eq && c) return new Value.VBool(false);
                    }
                    return new Value.VBool(true);
                });
            }
            case "<": case "<=": case ">": case ">=":
                return nary(t, args, env, depth, vs -> chainCmp(op, vs));
            case "+": return nary(t, args, env, depth, vs -> arith(vs, op));
            case "*": return nary(t, args, env, depth, vs -> arith(vs, op));
            case "-": return nary(t, args, env, depth, vs -> {
                if (vs.length == 1) return neg(vs[0]);
                return arith(vs, op);
            });
            case "div": case "mod": case "rem":
                return nary(t, args, env, depth, vs -> {
                    if (vs.length != 2) return Value.unsupported(op + "-arity");
                    if (!(vs[0] instanceof Value.VInt a) || !(vs[1] instanceof Value.VInt b)) return lift(vs, op);
                    if (b.n().equals(BigInteger.ZERO)) return Value.unknown("div-by-zero");
                    BigInteger q = a.n().divide(b.n());
                    BigInteger r = a.n().remainder(b.n());
                    if (op.equals("div")) return new Value.VInt(q);
                    if (op.equals("rem")) return new Value.VInt(r);
                    // mod: result sign follows divisor
                    if (!r.equals(BigInteger.ZERO) && (r.signum() != b.n().signum())) r = r.add(b.n());
                    return new Value.VInt(r);
                });
            case "to_real":
                return unary(t, args, env, depth, vs -> {
                    if (vs[0] instanceof Value.VInt i) return new Value.VReal(new BigDecimal(i.n()));
                    if (vs[0] instanceof Value.VReal) return vs[0];
                    return lift(vs, op);
                });
            case "abs":
                return unary(t, args, env, depth, vs -> {
                    if (vs[0] instanceof Value.VInt i) return new Value.VInt(i.n().abs());
                    if (vs[0] instanceof Value.VReal r) return new Value.VReal(r.d().abs());
                    return lift(vs, op);
                });
            case "ite": {
                if (args.size() != 3) return leaf(t, Value.unsupported("ite-arity"));
                Out c = eval(args.get(0), env, depth + 1);
                EvalNode n;
                if (c.value instanceof Value.VBool b) {
                    Out branch = eval(args.get(b.b() ? 1 : 2), env, depth + 1);
                    n = node(t, branch.value);
                    n.children.add(c.node); n.children.add(branch.node);
                    return new Out(branch.value, n);
                }
                n = node(t, lift(new Value[]{c.value}, "ite"));
                n.children.add(c.node);
                return new Out(lift(new Value[]{c.value}, "ite"), n);
            }
            case "let": {
                if (args.size() != 2) return leaf(t, Value.unsupported("let-arity"));
                Env inner = env;
                List<EvalNode> binds = new ArrayList<>();
                for (SExpr b : args.get(0).items()) {
                    String name = b.get(0).atom();
                    Out v = eval(b.get(1), inner, depth + 1);
                    binds.add(v.node);
                    inner = inner.withConst(name, v.value);
                }
                Out body = eval(args.get(1), inner, depth + 1);
                EvalNode n = node(t, body.value);
                n.children.addAll(binds); n.children.add(body.node);
                return new Out(body.value, n);
            }
            case "select": {
                if (args.size() != 2) return leaf(t, Value.unsupported("select-arity"));
                Out arr = eval(args.get(0), env, depth + 1);
                Out idx = eval(args.get(1), env, depth + 1);
                Value r;
                if (arr.value instanceof Value.VArray a) r = a.select(idx.value);
                else r = lift(new Value[]{arr.value, idx.value}, "select");
                EvalNode n = node(t, r);
                n.children.add(arr.node); n.children.add(idx.node);
                return new Out(r, n);
            }
            case "store": {
                if (args.size() != 3) return leaf(t, Value.unsupported("store-arity"));
                Out arr = eval(args.get(0), env, depth + 1);
                Out idx = eval(args.get(1), env, depth + 1);
                Out val = eval(args.get(2), env, depth + 1);
                Value r;
                if (arr.value instanceof Value.VArray a) {
                    Map<String, Value> entries = new LinkedHashMap<>(a.entries());
                    entries.put(Value.show(idx.value), val.value);
                    r = new Value.VArray(a.defaultValue(), entries);
                } else r = lift(new Value[]{arr.value}, "store");
                EvalNode n = node(t, r);
                n.children.add(arr.node); n.children.add(idx.node); n.children.add(val.node);
                return new Out(r, n);
            }
            case "forall": case "exists":
                return leaf(t, Value.unsupported("theory:quantifiers"));
            default: {
                // string / bitvector / other theory operators
                String theory = theoryOfOp(op);
                if (theory != null) return leaf(t, Value.unsupported("theory:" + theory));
                // defined function?
                FunDef fd = env.funs.get(op);
                if (fd != null) {
                    if (fd.params().size() != args.size()) return leaf(t, Value.unsupported("fun-arity:" + op));
                    Env inner = env;
                    List<EvalNode> argNodes = new ArrayList<>();
                    for (int i = 0; i < args.size(); i++) {
                        Out av = eval(args.get(i), env, depth + 1);
                        argNodes.add(av.node);
                        inner = inner.withConst(fd.params().get(i), av.value);
                    }
                    Out body = eval(fd.body, inner, depth + 1);
                    EvalNode n = node(t, body.value);
                    n.children.addAll(argNodes); n.children.add(body.node);
                    return new Out(body.value, n);
                }
                if (env.declared.contains(op)) return leaf(t, Value.unsupported("uninterpreted-function:" + op));
                return leaf(t, Value.unsupported("unknown-operator:" + op));
            }
        }
    }

    private Out atom(String s, Env env) {
        if (s.equals("true")) return leaf(SExpr.atom(s), new Value.VBool(true));
        if (s.equals("false")) return leaf(SExpr.atom(s), new Value.VBool(false));
        if (s.matches("-?\\d+")) return leaf(SExpr.atom(s), new Value.VInt(new BigInteger(s)));
        if (s.matches("-?\\d+\\.\\d+")) return leaf(SExpr.atom(s), new Value.VReal(new BigDecimal(s)));
        if (s.startsWith("#b") || s.startsWith("#x") || s.startsWith("bv"))
            return leaf(SExpr.atom(s), Value.unsupported("theory:bitvectors"));
        if (s.startsWith("\"")) return leaf(SExpr.atom(s), Value.unsupported("theory:strings"));
        Value v = env.consts.get(s);
        if (v != null) return leaf(SExpr.atom(s), v);
        if (env.declared.contains(s)) return leaf(SExpr.atom(s), Value.unknown("not-in-model:" + s));
        return leaf(SExpr.atom(s), Value.unknown("free-symbol:" + s));
    }

    // ---- helpers ----

    private interface Fn { Value apply(Value[] vs); }

    private Out unary(SExpr t, List<SExpr> args, Env env, int depth, Fn f) {
        return nary(t, args, env, depth, f);
    }

    private Out nary(SExpr t, List<SExpr> args, Env env, int depth, Fn f) {
        List<EvalNode> kids = new ArrayList<>();
        Value[] vs = new Value[args.size()];
        for (int i = 0; i < args.size(); i++) {
            Out o = eval(args.get(i), env, depth + 1);
            kids.add(o.node); vs[i] = o.value;
        }
        Value r = f.apply(vs);
        EvalNode n = node(t, r);
        n.children.addAll(kids);
        return new Out(r, n);
    }

    private Out naryBool(SExpr t, List<SExpr> args, Env env, int depth, boolean isAnd) {
        List<EvalNode> kids = new ArrayList<>();
        Value result = new Value.VBool(isAnd);
        for (SExpr a : args) {
            Out o = eval(a, env, depth + 1);
            kids.add(o.node);
            Boolean b = asBool(o.value);
            if (b == null) { result = lift(new Value[]{o.value}, isAnd ? "and" : "or"); }
            else if (isAnd && !b) { if (result instanceof Value.VBool) result = new Value.VBool(false); }
            else if (!isAnd && b) { if (result instanceof Value.VBool) result = new Value.VBool(true); }
        }
        EvalNode n = node(t, result);
        n.children.addAll(kids);
        return new Out(result, n);
    }

    private Out leaf(SExpr t, Value v) { return new Out(v, node(t, v)); }

    private EvalNode node(SExpr t, Value v) {
        String s = SExpr.print(t);
        if (s.length() > 80) s = s.substring(0, 77) + "...";
        return new EvalNode(s, Value.show(v), EvalNode.statusOf(v));
    }

    private static Boolean asBool(Value v) {
        if (v instanceof Value.VBool b) return b.b();
        return null;
    }

    private Value boolOp(Value v, java.util.function.Function<Boolean, Boolean> f) {
        Boolean b = asBool(v);
        if (b == null) return lift(new Value[]{v}, "not");
        return new Value.VBool(f.apply(b));
    }

    /** UNKNOWN/UNSUPPORTED propagate: unsupported wins over unknown. */
    static Value lift(Value[] vs, String ctx) {
        String unknown = null;
        for (Value v : vs) {
            if (v instanceof Value.VUnsupported u) return v;
            if (v instanceof Value.VUnknown u && unknown == null) unknown = u.reason();
        }
        return Value.unknown(ctx + ":" + (unknown != null ? unknown : "indeterminate"));
    }

    private static Boolean valueEq(Value a, Value b) {
        if (a instanceof Value.VBool x && b instanceof Value.VBool y) return x.b() == y.b();
        BigDecimal x = asNum(a), y = asNum(b);
        if (x != null && y != null) return x.compareTo(y) == 0;
        if (a instanceof Value.VArray || b instanceof Value.VArray) return null; // array equality outside subset
        return null;
    }

    private static BigDecimal asNum(Value v) {
        if (v instanceof Value.VInt i) return new BigDecimal(i.n());
        if (v instanceof Value.VReal r) return r.d();
        return null;
    }

    private Value chainCmp(String op, Value[] vs) {
        for (int i = 0; i + 1 < vs.length; i++) {
            BigDecimal a = asNum(vs[i]), b = asNum(vs[i + 1]);
            if (a == null || b == null) return lift(new Value[]{vs[i], vs[i + 1]}, op);
            int c = a.compareTo(b);
            boolean ok = switch (op) {
                case "<" -> c < 0; case "<=" -> c <= 0; case ">" -> c > 0; default -> c >= 0;
            };
            if (!ok) return new Value.VBool(false);
        }
        return new Value.VBool(true);
    }

    private Value arith(Value[] vs, String op) {
        boolean real = false;
        for (Value v : vs) {
            if (v instanceof Value.VReal) real = true;
            else if (!(v instanceof Value.VInt)) return lift(vs, op);
        }
        if (real) {
            BigDecimal acc = asNum(vs[0]);
            for (int i = 1; i < vs.length; i++) {
                BigDecimal x = asNum(vs[i]);
                acc = switch (op) {
                    case "+" -> acc.add(x);
                    case "-" -> acc.subtract(x);
                    default -> acc.multiply(x);
                };
            }
            return new Value.VReal(acc);
        }
        BigInteger acc = ((Value.VInt) vs[0]).n();
        for (int i = 1; i < vs.length; i++) {
            BigInteger x = ((Value.VInt) vs[i]).n();
            acc = switch (op) {
                case "+" -> acc.add(x);
                case "-" -> acc.subtract(x);
                default -> acc.multiply(x);
            };
        }
        return new Value.VInt(acc);
    }

    private Value neg(Value v) {
        if (v instanceof Value.VInt i) return new Value.VInt(i.n().negate());
        if (v instanceof Value.VReal r) return new Value.VReal(r.d().negate());
        return lift(new Value[]{v}, "-");
    }

    static String theoryOfOp(String op) {
        if (op.startsWith("str.") || op.startsWith("re.") || op.equals("str.to_re")) return "strings";
        if (op.startsWith("bv") || op.equals("concat") || op.equals("extract") || op.startsWith("fp.")) return "bitvectors";
        if (op.startsWith("fp") || op.startsWith("seq.")) return "floating-point";
        if (op.equals("/")) return "real-division";
        return null;
    }
}
