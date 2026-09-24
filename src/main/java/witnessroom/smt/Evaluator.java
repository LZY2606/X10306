package witnessroom.smt;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates terms in the local finite semantic subset
 * (Bool / Int / Real literals, arrays with defaults, core arithmetic).
 * Anything outside the subset yields UNKNOWN or UNSUPPORTED nodes with
 * diagnostics; nothing is silently dropped.
 */
public final class Evaluator {

    private static final Set<String> BOOL_OPS = Set.of("and", "or", "not", "=>", "xor");
    private static final Set<String> CMP_OPS = Set.of("=", "distinct", "<", "<=", ">", ">=");
    private static final Set<String> ARITH_OPS = Set.of("+", "-", "*", "div", "mod", "abs");

    private final Model model;
    private final Map<String, Value> overrides = new HashMap<>();

    public Evaluator(Model model) { this.model = model; }

    public Evaluator withAssignment(Map<String, Value> assignment) {
        Evaluator e = new Evaluator(model);
        e.overrides.putAll(assignment);
        return e;
    }

    public EvalNode eval(SExpr t) { return evalAt(t, ""); }

    private EvalNode evalAt(SExpr t, String path) {
        EvalNode node = new EvalNode(path.isEmpty() ? "/" : path, SExpr.render(t));
        if (t instanceof SExpr.Atom a) {
            evalAtom(a.value(), node);
            return node;
        }
        SExpr.Lst l = (SExpr.Lst) t;
        if (l.items().isEmpty()) { unknown(node, "empty form"); return node; }
        if (!(l.items().get(0) instanceof SExpr.Atom head)) {
            // ((as const (Array Int Int)) v)
            if (l.items().get(0) instanceof SExpr.Lst inner && !inner.items().isEmpty()
                    && SExpr.isAtom(inner.items().get(0), "as")
                    && inner.items().size() >= 3 && SExpr.isAtom(inner.items().get(1), "const")) {
                EvalNode child = evalAt(l.items().get(1), childPath(node, 0));
                node.children.add(child);
                if (child.status == EvalNode.Status.VALUE) {
                    node.status = EvalNode.Status.VALUE;
                    node.value = new Value.ArrayV(child.value, Map.of());
                } else propagate(node, child);
                return node;
            }
            unsupported(node, "unknown", "non-atomic head: " + SExpr.render(l.items().get(0)));
            return node;
        }
        String op = head.value();
        List<SExpr> args = l.items().subList(1, l.items().size());
        for (int i = 0; i < args.size(); i++)
            node.children.add(evalAt(args.get(i), childPath(node, i)));

        if (BOOL_OPS.contains(op)) { evalBool(op, node); return node; }
        if (CMP_OPS.contains(op)) { evalCmp(op, node); return node; }
        if (ARITH_OPS.contains(op)) { evalArith(op, node); return node; }
        switch (op) {
            case "ite" -> { evalIte(node); return node; }
            case "select" -> { evalSelect(node); return node; }
            case "store" -> { evalStore(node); return node; }
            case "to_real" -> {
                EvalNode c = node.children.get(0);
                if (c.status == EvalNode.Status.VALUE && c.value instanceof Value.IntV iv) {
                    node.status = EvalNode.Status.VALUE;
                    node.value = new Value.RealV(iv.v().toString() + ".0", iv.v().doubleValue());
                } else if (c.status != EvalNode.Status.VALUE) propagate(node, c);
                else unknown(node, "to_real of non-integer");
                return node;
            }
            case "!" -> {
                // annotations are transparent for evaluation
                EvalNode c = node.children.get(0);
                node.status = c.status; node.value = c.value; node.reason = c.reason; node.theory = c.theory;
                return node;
            }
            default -> {
                String theory = theoryOf(op);
                unsupported(node, theory, "operator '" + op + "' is outside the local finite subset"
                        + (theory.equals("unknown") ? "" : " (theory: " + theory + ")"));
                return node;
            }
        }
    }

    private static String childPath(EvalNode parent, int i) {
        return (parent.path.equals("/") ? "" : parent.path) + "/" + i;
    }

    private void evalAtom(String s, EvalNode node) {
        if (s.equals("true")) { node.status = EvalNode.Status.VALUE; node.value = new Value.BoolV(true); return; }
        if (s.equals("false")) { node.status = EvalNode.Status.VALUE; node.value = new Value.BoolV(false); return; }
        if (s.matches("-?\\d+")) { node.status = EvalNode.Status.VALUE; node.value = new Value.IntV(new BigInteger(s)); return; }
        if (s.matches("-?\\d+\\.\\d+")) {
            node.status = EvalNode.Status.VALUE;
            node.value = new Value.RealV(s, Double.parseDouble(s));
            return;
        }
        Value ov = overrides.get(s);
        if (ov != null) { node.status = EvalNode.Status.VALUE; node.value = ov; return; }
        Model.Entry e = model.lookup(s);
        if (e == null) { unknown(node, "no definition in model (partial model) for '" + s + "'"); return; }
        if (e.value() instanceof Value.RawV rv) {
            unsupported(node, theoryOfSort(e.sort()), rv.diagnostic());
            return;
        }
        node.status = EvalNode.Status.VALUE;
        node.value = e.value();
    }

    private void evalBool(String op, EvalNode node) {
        Boolean acc = null;
        for (EvalNode c : node.children) {
            if (c.status != EvalNode.Status.VALUE || !(c.value instanceof Value.BoolV bv)) {
                if (node.reason == null) propagate(node, c);
                if (op.equals("not") || node.children.size() == 1) return;
                continue;
            }
            boolean b = bv.v();
            acc = switch (op) {
                case "not" -> !b;
                case "and" -> acc == null ? b : acc && b;
                case "or" -> acc == null ? b : acc || b;
                case "=>" -> {
                    if (acc == null) yield b;
                    boolean prev = acc;
                    yield !prev || b;
                }
                case "xor" -> acc == null ? b : acc ^ b;
                default -> acc;
            };
        }
        if (node.reason != null) return;
        if (acc == null) { unknown(node, "no operands"); return; }
        node.status = EvalNode.Status.VALUE;
        node.value = new Value.BoolV(acc);
    }

    private void evalCmp(String op, EvalNode node) {
        for (EvalNode c : node.children) {
            if (c.status != EvalNode.Status.VALUE) { propagate(node, c); return; }
        }
        List<EvalNode> cs = node.children;
        if (cs.size() < 2) { unknown(node, "comparison needs at least 2 operands"); return; }
        boolean result = true;
        for (int i = 0; i + 1 < cs.size(); i++) {
            Value a = cs.get(i).value, b = cs.get(i + 1).value;
            int cmp = compare(a, b);
            if (cmp == Integer.MIN_VALUE) { unknown(node, "incomparable values: " + Value.render(a) + " vs " + Value.render(b)); return; }
            boolean r = switch (op) {
                case "=" -> cmp == 0;
                case "distinct" -> cmp != 0;
                case "<" -> cmp < 0;
                case "<=" -> cmp <= 0;
                case ">" -> cmp > 0;
                case ">=" -> cmp >= 0;
                default -> false;
            };
            if (op.equals("distinct")) {
                // pairwise distinct
                for (int x = 0; x < cs.size(); x++)
                    for (int y = x + 1; y < cs.size(); y++)
                        if (compare(cs.get(x).value, cs.get(y).value) == 0) { result = false; break; }
                break;
            }
            if (!r) { result = false; break; }
        }
        node.status = EvalNode.Status.VALUE;
        node.value = new Value.BoolV(result);
    }

    private static int compare(Value a, Value b) {
        if (a instanceof Value.IntV x && b instanceof Value.IntV y) return x.v().compareTo(y.v());
        if (a instanceof Value.BoolV x && b instanceof Value.BoolV y) return Boolean.compare(x.v(), y.v());
        double da = a instanceof Value.IntV x ? x.v().doubleValue() : a instanceof Value.RealV x ? x.approx() : Double.NaN;
        double db = b instanceof Value.IntV y ? y.v().doubleValue() : b instanceof Value.RealV y ? y.approx() : Double.NaN;
        if (Double.isNaN(da) || Double.isNaN(db)) return Integer.MIN_VALUE;
        return Double.compare(da, db);
    }

    private void evalArith(String op, EvalNode node) {
        for (EvalNode c : node.children) {
            if (c.status != EvalNode.Status.VALUE) { propagate(node, c); return; }
            if (!(c.value instanceof Value.IntV)) { unknown(node, "arithmetic on non-integer value"); return; }
        }
        List<EvalNode> cs = node.children;
        BigInteger acc = ((Value.IntV) cs.get(0).value).v();
        switch (op) {
            case "abs" -> acc = acc.abs();
            case "-" -> {
                if (cs.size() == 1) acc = acc.negate();
                else for (int i = 1; i < cs.size(); i++) acc = acc.subtract(intOf(cs.get(i)));
            }
            case "+" -> { for (int i = 1; i < cs.size(); i++) acc = acc.add(intOf(cs.get(i))); }
            case "*" -> { for (int i = 1; i < cs.size(); i++) acc = acc.multiply(intOf(cs.get(i))); }
            case "div", "mod" -> {
                BigInteger b = intOf(cs.get(1));
                if (b.signum() == 0) { unknown(node, op + " by zero is uninterpreted"); return; }
                // Euclidean semantics per SMT-LIB
                BigInteger[] dm = acc.divideAndRemainder(b.abs());
                if (dm[1].signum() < 0) dm[1] = dm[1].add(b.abs());
                BigInteger q = acc.subtract(dm[1]).divide(b);
                acc = op.equals("div") ? q : dm[1];
            }
            default -> { unknown(node, "unsupported arithmetic op " + op); return; }
        }
        node.status = EvalNode.Status.VALUE;
        node.value = new Value.IntV(acc);
    }

    private static BigInteger intOf(EvalNode n) { return ((Value.IntV) n.value).v(); }

    private void evalIte(EvalNode node) {
        if (node.children.size() != 3) { unknown(node, "ite needs 3 operands"); return; }
        EvalNode c = node.children.get(0);
        if (c.status != EvalNode.Status.VALUE || !(c.value instanceof Value.BoolV bv)) { propagate(node, c); return; }
        EvalNode branch = node.children.get(bv.v() ? 1 : 2);
        if (branch.status == EvalNode.Status.VALUE) { node.status = EvalNode.Status.VALUE; node.value = branch.value; }
        else propagate(node, branch);
    }

    private void evalSelect(EvalNode node) {
        if (node.children.size() != 2) { unknown(node, "select needs 2 operands"); return; }
        EvalNode arr = node.children.get(0), idx = node.children.get(1);
        if (arr.status != EvalNode.Status.VALUE) { propagate(node, arr); return; }
        if (idx.status != EvalNode.Status.VALUE) { propagate(node, idx); return; }
        if (!(arr.value instanceof Value.ArrayV av)) { unknown(node, "select on non-array"); return; }
        String key = Value.render(idx.value);
        node.status = EvalNode.Status.VALUE;
        node.value = av.entries().getOrDefault(key, av.dflt());
    }

    private void evalStore(EvalNode node) {
        if (node.children.size() != 3) { unknown(node, "store needs 3 operands"); return; }
        EvalNode arr = node.children.get(0), idx = node.children.get(1), val = node.children.get(2);
        for (EvalNode c : List.of(arr, idx, val))
            if (c.status != EvalNode.Status.VALUE) { propagate(node, c); return; }
        if (!(arr.value instanceof Value.ArrayV av)) { unknown(node, "store on non-array"); return; }
        Map<String, Value> entries = new HashMap<>(av.entries());
        entries.put(Value.render(idx.value), val.value);
        node.status = EvalNode.Status.VALUE;
        node.value = new Value.ArrayV(av.dflt(), entries);
    }

    private static void unknown(EvalNode node, String reason) {
        node.status = EvalNode.Status.UNKNOWN;
        node.reason = reason;
    }

    private static void unsupported(EvalNode node, String theory, String reason) {
        node.status = EvalNode.Status.UNSUPPORTED;
        node.theory = theory;
        node.reason = reason;
    }

    private static void propagate(EvalNode node, EvalNode child) {
        if (child.status == EvalNode.Status.UNSUPPORTED) {
            node.status = EvalNode.Status.UNSUPPORTED;
            node.theory = child.theory;
            node.reason = child.reason;
        } else {
            node.status = EvalNode.Status.UNKNOWN;
            node.reason = child.reason;
        }
    }

    public static String theoryOf(String op) {
        if (op.startsWith("bv") || op.startsWith("concat") || op.startsWith("extract")) return "FixedSizeBitVectors";
        if (op.startsWith("str.") || op.startsWith("re.")) return "Strings";
        if (op.equals("forall") || op.equals("exists")) return "Quantifiers";
        if (op.startsWith("fp.") || op.startsWith("to_fp")) return "FloatingPoint";
        return "unknown";
    }

    public static String theoryOfSort(String sort) {
        if (sort.contains("BitVec")) return "FixedSizeBitVectors";
        if (sort.contains("String")) return "Strings";
        if (sort.contains("Float")) return "FloatingPoint";
        return "unknown";
    }
}
