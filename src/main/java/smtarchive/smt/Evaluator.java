package smtarchive.smt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates terms under a model within the supported fragment. Every evaluation
 * produces an EvalNode tree: unsupported sub-theories are marked explicitly and
 * never silently treated as true/false.
 */
public class Evaluator {
    private final SmtModel model;
    private final Map<String, Command.DeclareFun> symbolSorts;
    private final Map<String, Command.DefineFun> macros;
    private int nodeCounter = 0;

    public Evaluator(SmtModel model,
                     Map<String, Command.DeclareFun> symbolSorts,
                     Map<String, Command.DefineFun> macros) {
        this.model = model;
        this.symbolSorts = symbolSorts;
        this.macros = macros;
    }

    public record Result(Value value, EvalNode tree, List<String> diagnostics) {}

    public Result evaluate(Term term) {
        List<String> diag = new ArrayList<>();
        Env env = new Env(null);
        Value v = eval(term, env, diag);
        return new Result(v, lastTree == null ? null : lastTree, diag);
    }

    private EvalNode lastTree;

    private static final class Env {
        final Env parent;
        final Map<String, Value> vars = new LinkedHashMap<>();
        Env(Env parent) { this.parent = parent; }
        Value lookup(String name) {
            for (Env e = this; e != null; e = e.parent) if (e.vars.containsKey(name)) return e.vars.get(name);
            return null;
        }
    }

    private Value eval(Term t, Env env, List<String> diag) {
        int id = nodeCounter++;
        List<EvalNode> kids = new ArrayList<>();
        Value v = evalInner(t, env, diag, kids);
        boolean supported = !(v instanceof Value.UnsupportedValue);
        List<String> nodeDiag = supported ? List.of() : List.of(((Value.UnsupportedValue) v).reason());
        lastTree = new EvalNode("ev" + id, shortText(t), v.render(), supported, new ArrayList<>(nodeDiag), kids);
        return v;
    }

    private Value evalInner(Term t, Env env, List<String> diag, List<EvalNode> kids) {
        switch (t) {
            case Term.Num n -> { return new Value.Num(n.value(), n.isInt()); }
            case Term.BoolLit b -> { return new Value.Bool(b.value()); }
            case Term.Sym s -> {
                Value local = env.lookup(s.name());
                if (local != null) return local;
                if ("true".equals(s.name())) return new Value.Bool(true);
                if ("false".equals(s.name())) return new Value.Bool(false);
                SmtModel.ModelDef def = model == null ? null : model.byName().get(s.name());
                if (def != null) return evalDef(def, env, diag, kids);
                String reason = "symbol '" + s.name() + "' has no model definition (partial model)";
                diag.add(reason);
                return new Value.UnsupportedValue(reason, s.name());
            }
            case Term.Let lt -> {
                Env nested = new Env(env);
                for (Term.Let.Binding b : lt.bindings()) nested.vars.put(b.name(), eval(b.value(), nested, diag));
                return eval(lt.body(), nested, diag);
            }
            case Term.Annot an -> {
                Value inner = eval(an.inner(), env, diag);
                kids.add(lastTree);
                return inner;
            }
            case Term.Unsupported u -> {
                diag.add(u.description());
                return new Value.UnsupportedValue("unsupported theory: " + u.kind(), u.raw());
            }
            case Term.App app -> { return evalApp(app, env, diag, kids); }
        }
    }

    private Value evalDef(SmtModel.ModelDef def, Env env, List<String> diag, List<EvalNode> kids) {
        return switch (def.body()) {
            case SmtModel.DefBody.Value v -> eval(v.term(), env, diag);
            case SmtModel.DefBody.ArrayConst ac -> {
                Value d = eval(ac.defaultTerm(), env, diag);
                Sort arr = def.sort();
                Sort idx = arr.params().isEmpty() ? Sort.UNKNOWN : arr.params().get(0);
                Sort elem = arr.params().size() < 2 ? Sort.UNKNOWN : arr.params().get(1);
                return new Value.Array(new LinkedHashMap<>(), d, idx, elem);
            }
            case SmtModel.DefBody.ArrayStore as -> {
                Sort arr = def.sort();
                Sort idx = arr.params().isEmpty() ? Sort.UNKNOWN : arr.params().get(0);
                Sort elem = arr.params().size() < 2 ? Sort.UNKNOWN : arr.params().get(1);
                Map<Value, Value> stores = new LinkedHashMap<>();
                for (SmtModel.ArrayEntry e : as.entries())
                    stores.put(eval(e.index(), env, diag), eval(e.value(), env, diag));
                Value d = eval(as.defaultTerm(), env, diag);
                return new Value.Array(stores, d, idx, elem);
            }
            case SmtModel.DefBody.Lambda lm -> new Value.UnsupportedValue(
                    "function model '" + def.name() + "' evaluated without arguments", def.raw());
            case SmtModel.DefBody.Other o -> new Value.UnsupportedValue("abstract/uninterpreted model entry", def.raw());
        };
    }

    private Value evalApp(Term.App app, Env env, List<String> diag, List<EvalNode> kids) {
        String op = app.op();
        List<Value> vs = new ArrayList<>();
        for (Term a : app.args()) { Value x = eval(a, env, diag); vs.add(x); kids.add(lastTree); }

        if ("const-array".equals(op)) {
            if (vs.size() == 1) return new Value.Array(new LinkedHashMap<>(), vs.get(0), Sort.UNKNOWN, Sort.UNKNOWN);
            return new Value.UnsupportedValue("malformed const array", app.raw());
        }
        // defined function macro application
        Command.DefineFun macro = macros.get(op);
        if (macro != null && macro.params().size() == vs.size()) {
            Env call = new Env(env);
            for (int i = 0; i < vs.size(); i++) call.vars.put(macro.params().get(i).name(), vs.get(i));
            return eval(macro.body(), call, diag);
        }
        if (vs.stream().anyMatch(v -> v instanceof Value.UnsupportedValue)) {
            return new Value.UnsupportedValue("propagation: an operand uses an unsupported theory", app.raw());
        }

        try {
            switch (op) {
                case "not" -> { return new Value.Bool(!asBool(vs.get(0))); }
                case "and" -> {
                    boolean r = true; for (Value v : vs) r &= asBool(v); return new Value.Bool(r);
                }
                case "or" -> {
                    boolean r = false; for (Value v : vs) r |= asBool(v); return new Value.Bool(r);
                }
                case "xor" -> { return new Value.Bool(asBool(vs.get(0)) ^ asBool(vs.get(1))); }
                case "=>" -> { return new Value.Bool(!asBool(vs.get(0)) || asBool(vs.get(1))); }
                case "=" -> {
                    if (vs.size() != 2) return bad("= needs 2 args", app);
                    return new Value.Bool(valueEquals(vs.get(0), vs.get(1)));
                }
                case "distinct" -> {
                    for (int i = 0; i < vs.size(); i++)
                        for (int j = i + 1; j < vs.size(); j++)
                            if (valueEquals(vs.get(i), vs.get(j))) return new Value.Bool(false);
                    return new Value.Bool(true);
                }
                case "<", ">", "<=", ">=" -> {
                    Rational a = asNum(vs.get(0)), b = asNum(vs.get(1));
                    int cmp = a.compareTo(b);
                    return new Value.Bool(switch (op) {
                        case "<" -> cmp < 0; case ">" -> cmp > 0;
                        case "<=" -> cmp <= 0; default -> cmp >= 0;
                    });
                }
                case "+" -> { return foldNums(vs, Rational.ZERO, Rational::add, anyInt(vs)); }
                case "-" -> {
                    if (vs.size() == 1) return new Value.Num(asNum(vs.get(0)).neg(), ((Value.Num) vs.get(0)).isInt());
                    Rational r = asNum(vs.get(0));
                    for (int i = 1; i < vs.size(); i++) r = r.sub(asNum(vs.get(i)));
                    return new Value.Num(r, anyInt(vs));
                }
                case "*" -> {
                    boolean linear = true;
                    for (Value v : vs) if (!(v instanceof Value.Num nn && (nn.r().equals(Rational.ZERO) || isConcrete(nn)))) linear = false;
                    if (!linear) return new Value.UnsupportedValue("non-linear arithmetic is outside the supported fragment", app.raw());
                    Rational r = Rational.ONE;
                    for (Value v : vs) r = r.mul(asNum(v));
                    return new Value.Num(r, anyInt(vs));
                }
                case "/" -> {
                    // only constant divisor is supported by the evaluator
                    if (vs.size() == 2 && vs.get(1) instanceof Value.Num d) {
                        Rational a = asNum(vs.get(0));
                        return new Value.Num(Rational.of(a.numerator() * d.denominator(), a.denominator() * d.numerator()),
                                ((Value.Num) vs.get(0)).isInt() && d.isInt() && a.denominator() == 1);
                    }
                    return new Value.UnsupportedValue("non-constant division", app.raw());
                }
                case "ite" -> { return asBool(vs.get(0)) ? vs.get(1) : vs.get(2); }
                case "select" -> {
                    if (vs.get(0) instanceof Value.Array arr) {
                        Value got = arr.stores().get(vs.get(1));
                        Value r = got != null ? got : arr.defaultValue();
                        if (got == null) diag.add("array index not enumerated; applied explicit default value");
                        return r;
                    }
                    return new Value.UnsupportedValue("select on non-array", app.raw());
                }
                case "store" -> {
                    if (vs.get(0) instanceof Value.Array arr) {
                        Map<Value, Value> ns = new LinkedHashMap<>(arr.stores());
                        ns.put(vs.get(1), vs.get(2));
                        return new Value.Array(ns, arr.defaultValue(), arr.indexSort(), arr.elemSort());
                    }
                    return new Value.UnsupportedValue("store on non-array", app.raw());
                }
                default -> {
                    // model-defined function application
                    SmtModel.ModelDef def = model == null ? null : model.byName().get(op);
                    if (def != null && def.body() instanceof SmtModel.DefBody.Lambda lm
                            && lm.params().size() == vs.size()) {
                        Env call = new Env(env);
                        for (int i = 0; i < vs.size(); i++) call.vars.put(lm.params().get(i).name(), vs.get(i));
                        return eval(lm.body(), call, diag);
                    }
                    return new Value.UnsupportedValue("operator/function '" + op + "' is not supported", app.raw());
                }
            }
        } catch (ArithMismatch ex) {
            return new Value.UnsupportedValue(ex.getMessage(), app.raw());
        }
    }

    private static boolean isConcrete(Value.Num n) { return true; }

    private boolean anyInt(List<Value> vs) {
        for (Value v : vs) if (v instanceof Value.Num nn && nn.isInt()) return true;
        return false;
    }

    private Value.Num foldNums(List<Value> vs, Rational seed, java.util.function.BinaryOperator<Rational> f, boolean isInt) {
        Rational r = seed;
        for (Value v : vs) r = f.apply(r, asNum(v));
        return new Value.Num(r, isInt);
    }

    private boolean asBool(Value v) {
        if (v instanceof Value.Bool b) return b.b();
        throw new ArithMismatch("expected Boolean, got " + v.render());
    }
    private Rational asNum(Value v) {
        if (v instanceof Value.Num n) return n.r();
        throw new ArithMismatch("expected number, got " + v.render());
    }

    private boolean valueEquals(Value a, Value b) {
        if (a instanceof Value.Num x && b instanceof Value.Num y) return x.r().equals(y.r());
        if (a instanceof Value.Bool x && b instanceof Value.Bool y) return x.b() == y.b();
        if (a instanceof Value.Uninterpreted x && b instanceof Value.Uninterpreted y) return x.name().equals(y.name());
        return a.render().equals(b.render());
    }

    private Value bad(String why, Term.App app) { return new Value.UnsupportedValue(why, app.raw()); }

    private static String shortText(Term t) {
        String s = t.toString().replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    private static final class ArithMismatch extends RuntimeException {
        ArithMismatch(String m) { super(m); }
    }
}
