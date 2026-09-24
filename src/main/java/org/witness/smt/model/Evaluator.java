package org.witness.smt.model;

import org.witness.smt.parse.SExpr;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates assertions against a parsed model using the local semantic
 * fragment.  Produces a tree ({@link EvalNode}) so the UI can show every
 * intermediate value.  Unknown leaves (partial model), unsupported theories
 * and unresolved labels are first-class statuses, never errors.
 */
public final class Evaluator {

    /** Free boolean atom discovered during evaluation. */
    public record FreeBool(String name, String unit) {}

    private final ModelDef model;
    private final Map<String, String> units;
    private final Deque<Map<String, Value>> letScopes = new ArrayDeque<>();
    /** Unmodelled boolean constants, in first-seen order. */
    public final List<FreeBool> freeBools = new ArrayList<>();
    private final Map<String, Integer> freeIndex = new HashMap<>();

    public Evaluator(ModelDef model, Map<String, String> units) {
        this.model = model;
        this.units = units == null ? Map.of() : units;
    }

    public EvalNode evalTree(String rootId, SExpr term) {
        EvalNode node = new EvalNode(rootId, term);
        String unsupported = TheorySupport.firstUnsupported(term);
        if (unsupported != null) {
            node.status = EvalNode.Status.UNSUPPORTED_THEORY;
            node.reason = unsupported;
            return node;
        }
        Value v;
        try {
            v = eval(term, node);
        } catch (UnsupportedTheory u) {
            node.status = EvalNode.Status.UNSUPPORTED_THEORY;
            node.reason = u.feature;
            return node;
        } catch (EvalProblem p) {
            node.status = p.status;
            node.reason = p.getMessage();
            return node;
        }
        node.value = v;
        return node;
    }

    static final class UnsupportedTheory extends RuntimeException {
        final String feature;
        UnsupportedTheory(String f) { super(f); this.feature = f; }
        @Override public Throwable fillInStackTrace() { return this; }
    }
    static final class EvalProblem extends RuntimeException {
        final EvalNode.Status status;
        EvalProblem(EvalNode.Status s, String m) { super(m); this.status = s; }
        @Override public Throwable fillInStackTrace() { return this; }
    }

    private Value eval(SExpr term, EvalNode node) {
        if (term instanceof SExpr.Atom a) return evalAtom(a, node);
        SExpr.List l = (SExpr.List) term;
        String op = l.headName();
        return switch (op) {
            case "!" -> {
                EvalNode child = childNode(node, l.items().get(1));
                Value v = eval(l.items().get(1), child);
                node.children.add(child);
                yield v;
            }
            case "let" -> evalLet(l, node);
            case "=", "<", ">", "<=", ">=", "distinct" -> evalCompare(op, l, node);
            case "and", "or", "xor", "=>", "not", "iff" -> evalBool(op, l, node);
            case "ite" -> evalIte(l, node);
            case "+", "-", "*", "div", "mod", "abs" -> evalArith(op, l, node);
            case "select", "store" -> evalArray(op, l, node);
            case "as" -> evalAs(l, node);
            case "_" -> evalIndexed(l, node);
            default -> evalApp(op, l, node);
        };
    }

    private EvalNode childNode(EvalNode parent, SExpr term) {
        return new EvalNode(parent.id + "." + parent.children.size(), term);
    }

    private Value evalChild(SExpr term, EvalNode parent) {
        EvalNode c = childNode(parent, term);
        Value v = eval(term, c);
        c.value = v;
        attachUnit(c, term);
        parent.children.add(c);
        return v;
    }

    private void attachUnit(EvalNode node, SExpr term) {
        if (term instanceof SExpr.Atom a) {
            String u = units.get(unquote(a.token()));
            if (u != null && !u.isBlank()) node.unit = u;
        }
    }

    private Value evalAtom(SExpr.Atom a, EvalNode node) {
        String t = a.token();
        Value local = lookupLet(t);
        if (local != null) { node.unit = units.get(unquote(t)); return local; }
        if (t.equals("true")) return Value.ofBool(true);
        if (t.equals("false")) return Value.ofBool(false);
        if (isNumber(t)) return new Value.Int(Rat.parse(t));
        ModelDef.FunDef f = model.byName.get(t);
        if (f != null) {
            if (!f.isConstant())
                throw new EvalProblem(EvalNode.Status.BAD_SORT,
                        "function '" + t + "' used without arguments");
            Value v = eval(f.body(), node);
            if (f.body() instanceof SExpr.Atom) attachUnit(node, f.body());
            return v;
        }
        // Uninterpreted-element literal (as const.x U) also arrives as bare atom in some models
        if (t.startsWith("|") && t.endsWith("|")) {
            return registerFree(t, node, true);
        }
        return registerFree(t, node, false);
    }

    private Value registerFree(String t, EvalNode node, boolean element) {
        if (model.isPartial(t) || !isProbablyConst(t)) {
            // leave unknown
        }
        // Anything not defined in a (get-model) answer is an unmodelled constant.
        if (!freeIndex.containsKey(t)) {
            freeIndex.put(t, freeBools.size());
            freeBools.add(new FreeBool(t, units.getOrDefault(unquote(t), "")));
        }
        node.status = EvalNode.Status.UNMODELED;
        node.reason = element ? "uninterpreted-element" : "partial-model: symbol not defined in model";
        node.unit = units.getOrDefault(unquote(t), "");
        throw new EvalProblem(EvalNode.Status.UNMODELED, node.reason);
    }

    private boolean isProbablyConst(String t) { return true; }

    private String unquote(String sym) {
        return sym.startsWith("|") && sym.endsWith("|") ? sym.substring(1, sym.length() - 1) : sym;
    }

    private Value lookupLet(String name) {
        for (Map<String, Value> scope : letScopes) {
            Value v = scope.get(name);
            if (v != null) return v;
        }
        return null;
    }

    private boolean isNumber(String t) {
        if (t.isEmpty()) return false;
        int start = (t.charAt(0) == '-') ? 1 : 0;
        boolean digit = false;
        for (int i = start; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isDigit(c)) digit = true;
            else if (c != '/' && c != '.') return false;
        }
        return digit;
    }

    private Value evalLet(SExpr.List l, EvalNode node) {
        SExpr.List bindings = (SExpr.List) l.items().get(1);
        Map<String, Value> scope = new HashMap<>();
        letScopes.push(scope);
        try {
            for (SExpr b : bindings.items()) {
                SExpr.List bl = (SExpr.List) b;
                String name = ((SExpr.Atom) bl.items().get(0)).token();
                EvalNode c = childNode(node, bl.items().get(1));
                Value v = eval(bl.items().get(1), c);
                c.value = v;
                node.children.add(c);
                scope.put(name, v);
            }
            Value result = null;
            for (int i = 2; i < l.items().size(); i++) result = evalChild(l.items().get(i), node);
            return result;
        } finally {
            letScopes.pop();
        }
    }

    private Value evalCompare(String op, SExpr.List l, EvalNode node) {
        List<Value> vs = new ArrayList<>();
        for (int i = 1; i < l.items().size(); i++) vs.add(evalChild(l.items().get(i), node));
        if (vs.get(0) instanceof Value.Element || vs.get(1) instanceof Value.Element) {
            Value.Element a = asElement(vs.get(0));
            Value.Element b = asElement(vs.get(1));
            boolean eq = a.name().equals(b.name());
            return Value.ofBool(op.equals("=") ? eq : op.equals("distinct") ? !eq : badComparison(op));
        }
        requireInts(op, vs);
        boolean result;
        Rat a = ((Value.Int) vs.get(0)).n();
        Rat b = ((Value.Int) vs.get(1)).n();
        result = switch (op) {
            case "=" -> a.compareTo(b) == 0;
            case "distinct" -> a.compareTo(b) != 0;
            case "<" -> a.lt(b);
            case ">" -> b.lt(a);
            case "<=" -> !b.lt(a);
            case ">=" -> !a.lt(b);
            default -> throw new EvalProblem(EvalNode.Status.BAD_SORT, "bad comparator " + op);
        };
        return Value.ofBool(result);
    }

    private boolean badComparison(String op) {
        throw new EvalProblem(EvalNode.Status.BAD_SORT, op + " is not defined on abstract elements");
    }

    private Value.Element asElement(Value v) {
        if (v instanceof Value.Element e) return e;
        throw new EvalProblem(EvalNode.Status.BAD_SORT, "expected uninterpreted element, got " + v);
    }

    private void requireInts(String op, List<Value> vs) {
        for (Value v : vs)
            if (!(v instanceof Value.Int))
                throw new EvalProblem(EvalNode.Status.BAD_SORT, op + " applied to non-integer " + v);
    }

    private Value evalBool(String op, SExpr.List l, EvalNode node) {
        List<Value> vs = new ArrayList<>();
        for (int i = 1; i < l.items().size(); i++) vs.add(evalChild(l.items().get(i), node));
        return switch (op) {
            case "not" -> Value.ofBool(!asBool(vs.get(0)));
            case "and" -> {
                boolean r = true;
                for (Value v : vs) r &= asBool(v);
                yield Value.ofBool(r);
            }
            case "or" -> {
                boolean r = false;
                for (Value v : vs) r |= asBool(v);
                yield Value.ofBool(r);
            }
            case "xor" -> Value.ofBool(asBool(vs.get(0)) ^ asBool(vs.get(1)));
            case "iff" -> Value.ofBool(asBool(vs.get(0)) == asBool(vs.get(1)));
            case "=>" -> Value.ofBool(!asBool(vs.get(0)) || asBool(vs.get(1)));
            default -> throw new EvalProblem(EvalNode.Status.UNKNOWN, "unknown boolean op " + op);
        };
    }

    private boolean asBool(Value v) {
        if (v instanceof Value.Bool b) return b.b();
        throw new EvalProblem(EvalNode.Status.BAD_SORT, "expected Bool, got " + v);
    }

    private Value evalIte(SExpr.List l, EvalNode node) {
        Value cond = evalChild(l.items().get(1), node);
        if (!(cond instanceof Value.Bool b))
            throw new EvalProblem(EvalNode.Status.BAD_SORT, "ite condition is not boolean");
        // evaluate only the selected branch; keep the skipped branch visible-but-unevaluated
        int chosen = b.b() ? 2 : 3;
        EvalNode c = childNode(node, l.items().get(chosen));
        Value v = eval(l.items().get(chosen), c);
        c.value = v;
        node.children.add(c);
        return v;
    }

    private Value evalArith(String op, SExpr.List l, EvalNode node) {
        List<Value> vs = new ArrayList<>();
        for (int i = 1; i < l.items().size(); i++) vs.add(evalChild(l.items().get(i), node));
        requireInts(op, vs);
        Rat r;
        if (op.equals("-") && vs.size() == 1) r = ((Value.Int) vs.get(0)).n().neg();
        else {
            r = ((Value.Int) vs.get(0)).n();
            for (int i = 1; i < vs.size(); i++) {
                Rat x = ((Value.Int) vs.get(i)).n();
                r = switch (op) {
                    case "+" -> r.add(x);
                    case "-" -> r.sub(x);
                    case "*" -> r.mul(x);
                    case "div" -> r.div(x);
                    case "mod" -> mod(r, x);
                    case "abs" -> r;
                    default -> throw new EvalProblem(EvalNode.Status.UNKNOWN, "bad arith op " + op);
                };
            }
            if (op.equals("abs")) r = ((Value.Int) vs.get(0)).n().lt(Rat.ZERO) ? r.neg() : r;
        }
        if ((op.equals("div") || op.equals("mod")) && !r.isIntegral())
            throw new EvalProblem(EvalNode.Status.BAD_SORT, "integer result expected for " + op);
        return new Value.Int(r);
    }

    private Rat mod(Rat a, Rat b) {
        Rat q = new Rat(a.floor().divide(b.floor()), null) {};
        // Euclidean-style modulo matching SMT-LIB (sign of divisor)
        Rat d = new Rat(a.floor(), Rat.ONE).div(new Rat(b.floor(), Rat.ONE));
        // implement directly with integers
        java.math.BigInteger ai = a.floor();
        java.math.BigInteger bi = b.floor();
        java.math.BigInteger[] qr = ai.divideAndRemainder(bi);
        return new Rat(qr[1], java.math.BigInteger.ONE);
    }
