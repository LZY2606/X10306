package chamber.engine;

import chamber.smt.SExpr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Evaluates terms of the local finite semantic subset under a (possibly partial) model.
 *  Unknown values propagate; unsupported theories are reported explicitly, never guessed. */
public final class Evaluator {

    /** Evaluation tree node for the model-evaluation view. */
    public record EvalTree(String term, String value, List<EvalTree> children) {}

    public record Outcome(Value value, EvalTree tree) {}

    private final Map<String, Value> assignment;

    public Evaluator(Map<String, Value> assignment) {
        this.assignment = assignment;
    }

    public Outcome eval(SExpr t) {
        if (t instanceof SExpr.Atom a) {
            return leaf(a);
        }
        List<SExpr> items = t.asList();
        if (items.isEmpty()) return unknown(t, "empty application");
        SExpr head = items.get(0);
        // ((as const (Array Int Int)) v) — array default value
        if (head instanceof SExpr.ListExpr hl && hl.items().size() == 3
                && hl.items().get(0) instanceof SExpr.Atom as && as.value().equals("as")
                && hl.items().get(1) instanceof SExpr.Atom c && c.value().equals("const")) {
            Outcome inner = eval(items.get(1));
            if (inner.value() instanceof Value.I iv) {
                Value arr = new Value.Arr(new TreeMap<>(), iv.v());
                return new Outcome(arr, new EvalTree(t.toString(), arr.display(), List.of(inner.tree())));
            }
            return unknown(t, "const array of non-Int default");
        }
        if (!(head instanceof SExpr.Atom op)) {
            return unknown(t, "unsupported term head: " + head);
        }
        List<Outcome> args = new ArrayList<>();
        for (int i = 1; i < items.size(); i++) args.add(eval(items.get(i)));
        Value v = apply(op.value(), args);
        List<EvalTree> children = args.stream().map(Outcome::tree).toList();
        return new Outcome(v, new EvalTree(t.toString(), v.display(), children));
    }

    private Outcome leaf(SExpr.Atom a) {
        Value lit = Value.parse(a.value());
        if (lit != null) return new Outcome(lit, new EvalTree(a.value(), lit.display(), List.of()));
        if (a.value().matches("-?\\d+\\.\\d+")) {
            return unknown(a, "unsupported theory: Real literal " + a.value());
        }
        if (a.value().startsWith("#x") || a.value().startsWith("#b")) {
            return unknown(a, "unsupported theory: BitVec literal " + a.value());
        }
        Value bound = assignment.get(a.value());
        if (bound != null) return new Outcome(bound, new EvalTree(a.value(), bound.display(), List.of()));
        return unknown(a, "unbound symbol " + a.value() + " (partial model or undeclared)");
    }

    private Outcome unknown(SExpr t, String reason) {
        return unknown(t.toString(), reason);
    }

    private Outcome unknown(String term, String reason) {
        Value v = new Value.Unknown(reason);
        return new Outcome(v, new EvalTree(term, v.display(), List.of()));
    }

    private Value apply(String op, List<Outcome> args) {
        // Unknown propagates, except where a connective can still decide (and/or/ite).
        switch (op) {
            case "not" -> {
                Value a = args.get(0).value();
                if (a instanceof Value.B b) return new Value.B(!b.v());
                return propagate("not", args);
            }
            case "and" -> {
                boolean sawUnknown = false;
                for (Outcome o : args) {
                    if (o.value() instanceof Value.B b && !b.v()) return new Value.B(false);
                    if (o.value().isUnknown()) sawUnknown = true;
                }
                return sawUnknown ? propagate("and", args) : new Value.B(true);
            }
            case "or" -> {
                boolean sawUnknown = false;
                for (Outcome o : args) {
                    if (o.value() instanceof Value.B b && b.v()) return new Value.B(true);
                    if (o.value().isUnknown()) sawUnknown = true;
                }
                return sawUnknown ? propagate("or", args) : new Value.B(false);
            }
            case "=>" -> {
                Value a = args.get(0).value();
                Value b = args.get(1).value();
                if (a instanceof Value.B ba && !ba.v()) return new Value.B(true);
                if (b instanceof Value.B bb && bb.v()) return new Value.B(true);
                if (a instanceof Value.B ba2 && b instanceof Value.B bb2) return new Value.B(!ba2.v() || bb2.v());
                return propagate("=>", args);
            }
            case "ite" -> {
                Value c = args.get(0).value();
                if (c instanceof Value.B bc) return args.get(bc.v() ? 1 : 2).value();
                return propagate("ite", args);
            }
            case "=" -> {
                for (Outcome o : args) if (o.value().isUnknown()) return propagate("=", args);
                for (int i = 1; i < args.size(); i++) {
                    if (!valueEq(args.get(0).value(), args.get(i).value())) return new Value.B(false);
                }
                return new Value.B(true);
            }
            case "distinct" -> {
                for (Outcome o : args) if (o.value().isUnknown()) return propagate("distinct", args);
                for (int i = 0; i < args.size(); i++)
                    for (int j = i + 1; j < args.size(); j++)
                        if (valueEq(args.get(i).value(), args.get(j).value())) return new Value.B(false);
                return new Value.B(true);
            }
            case "<", "<=", ">", ">=" -> {
                for (Outcome o : args) if (o.value().isUnknown()) return propagate(op, args);
                for (int i = 1; i < args.size(); i++) {
                    long x = asInt(args.get(i - 1).value());
                    long y = asInt(args.get(i).value());
                    boolean ok = switch (op) {
                        case "<" -> x < y;
                        case "<=" -> x <= y;
                        case ">" -> x > y;
                        default -> x >= y;
                    };
                    if (!ok) return new Value.B(false);
                }
                return new Value.B(true);
            }
            case "+" -> {
                Value u = firstUnknown(args);
                if (u != null) return u;
                long s = 0;
                for (Outcome o : args) s += asInt(o.value());
                return new Value.I(s);
            }
            case "-" -> {
                Value u = firstUnknown(args);
                if (u != null) return u;
                if (args.size() == 1) return new Value.I(-asInt(args.get(0).value()));
                long s = asInt(args.get(0).value());
                for (int i = 1; i < args.size(); i++) s -= asInt(args.get(i).value());
                return new Value.I(s);
            }
            case "*" -> {
                Value u = firstUnknown(args);
                if (u != null) return u;
                long s = 1;
                for (Outcome o : args) s *= asInt(o.value());
                return new Value.I(s);
            }
            case "mod" -> {
                Value u = firstUnknown(args);
                if (u != null) return u;
                long y = asInt(args.get(1).value());
                if (y == 0) return Value.unknown("mod by zero");
                return new Value.I(Math.floorMod(asInt(args.get(0).value()), y));
            }
            case "select" -> {
                Value arr = args.get(0).value();
                Value idx = args.get(1).value();
                if (arr instanceof Value.Arr a && idx instanceof Value.I i) {
                    return new Value.I(a.select(i.v()));
                }
                if (arr.isUnknown()) return arr;
                if (idx.isUnknown()) return idx;
                return Value.unknown("select on non-array");
            }
            case "store" -> {
                Value arr = args.get(0).value();
                Value idx = args.get(1).value();
                Value val = args.get(2).value();
                if (arr instanceof Value.Arr a && idx instanceof Value.I i && val instanceof Value.I v) {
                    TreeMap<Long, Long> copy = new TreeMap<>(a.stores());
                    copy.put(i.v(), v.v());
                    return new Value.Arr(copy, a.defaultValue());
                }
                Value u = firstUnknown(args);
                return u != null ? u : Value.unknown("store on non-array");
            }
            default -> {
                return Value.unknown("unsupported operator: " + op);
            }
        }
    }

    private static boolean valueEq(Value a, Value b) {
        if (a instanceof Value.B x && b instanceof Value.B y) return x.v() == y.v();
        if (a instanceof Value.I x && b instanceof Value.I y) return x.v() == y.v();
        if (a instanceof Value.Arr x && b instanceof Value.Arr y) {
            return x.defaultValue() == y.defaultValue() && x.stores().equals(y.stores());
        }
        return false;
    }

    private static long asInt(Value v) {
        if (v instanceof Value.I i) return i.v();
        throw new IllegalStateException("expected Int, got " + v.display());
    }

    private static Value firstUnknown(List<Outcome> args) {
        for (Outcome o : args) if (o.value().isUnknown()) return o.value();
        return null;
    }

    private static Value propagate(String op, List<Outcome> args) {
        Value u = firstUnknown(args);
        return u != null ? u : Value.unknown("cannot evaluate " + op);
    }

    /** Collects the free variable atoms of a term (atoms that are not literals or operators). */
    public static void freeVars(SExpr t, Map<String, SExpr> declarations, Map<String, Value> out) {
        // declarations maps name -> sort; out is filled with name -> null placeholder
        collectVars(t, declarations, out);
    }

    private static void collectVars(SExpr t, Map<String, SExpr> declarations, Map<String, Value> out) {
        if (t instanceof SExpr.Atom a) {
            if (Value.parse(a.value()) == null && declarations.containsKey(a.value())) {
                out.putIfAbsent(a.value(), null);
            }
            return;
        }
        for (SExpr e : t.asList()) collectVars(e, declarations, out);
    }
}
