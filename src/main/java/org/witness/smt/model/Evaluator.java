package org.witness.smt.model;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.witness.smt.SExpr;
import org.witness.smt.domain.BigRational;
import org.witness.smt.domain.Sort;
import org.witness.smt.domain.Values;
import org.witness.smt.domain.Values.ArrayV;
import org.witness.smt.domain.Values.BoolV;
import org.witness.smt.domain.Values.NumV;
import org.witness.smt.domain.Values.UnknownV;
import org.witness.smt.domain.Values.Value;
import org.witness.smt.replay.FunDef;
import org.witness.smt.replay.ReplayState;
import org.witness.smt.replay.ReplayState.SymbolInfo;

/**
 * Evaluates active assertion terms under a solver model in the local subset.
 * Every evaluation produces an {@link EvalTree} so the review UI can show the
 * value of every sub-term together with its origin. Unsupported theories and
 * missing (partial) model symbols become explicit UNKNOWN nodes.
 */
public final class Evaluator {

    private final ReplayState state;
    private final Model model;
    private final List<String> unsupportedMarks;
    private final List<String> diagnostics = new ArrayList<>();

    public Evaluator(ReplayState state, Model model, List<String> unsupportedMarks) {
        this.state = state;
        this.model = model;
        this.unsupportedMarks = unsupportedMarks;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public EvalTree evaluateAssertion(String assertionId, SExpr term) {
        EvalTree root = new EvalTree(term.toString());
        Value v = eval(term, Map.of(), root);
        root.valueText = Values.show(v);
        root.sortText = v.sort().name();
        root.sourceEntryId = assertionId;
        if (v instanceof UnknownV u) {
            root.status = EvalTree.Status.UNKNOWN;
            root.reason = u.reason();
        }
        root.assignPaths();
        return root;
    }

    private Value eval(SExpr e, Map<String, Value> locals, EvalTree node) {
        if (e.isAtom()) {
            return evalAtom(e, locals, node);
        }
        String head = e.head();
        node.label = e.toString();
        if (head == null) {
            return unknown(node, Sort.BOOL, "empty application");
        }
        return switch (head) {
            case "true", "false" -> evalAtom(e, locals, node);
            case "not" -> boolUnop(e, locals, node, v -> !v);
            case "and" -> boolNary(e, locals, node, true, (a, b) -> a && b);
            case "or" -> boolNary(e, locals, node, false, (a, b) -> a || b);
            case "xor" -> boolBin(e, locals, node, (a, b) -> a ^ b);
            case "=>" -> boolBin(e, locals, node, (a, b) -> !a || b);
            case "=" -> compare(e, locals, node, 0, true);
            case "distinct" -> distinct(e, locals, node);
            case "<" -> compare(e, locals, node, -1, false);
            case ">" -> compare(e, locals, node, 1, false);
            case "<=" -> compare(e, locals, node, 0, true, -1, 0);
            case ">=" -> compare(e, locals, node, 0, true, 0, 1);
            case "+", "-", "*", "/", "div", "mod", "abs" -> arithmetic(e, locals, node);
            case "ite" -> ite(e, locals, node);
            case "select" -> select(e, locals, node);
            case "store" -> unknown(node, Sort.BOOL, "store terms are not evaluated in assertions");
            case "!" -> annotated(e, locals, node);
            case "forall", "exists", "lambda", "^" ->
                    unsupported(node, Sort.BOOL, "quantifier/function-value theory is unsupported locally: " + head);
            default -> application(e, locals, node, head);
        };
    }

    private Value evalAtom(SExpr e, Map<String, Value> locals, EvalTree node) {
        String token = e.atom();
        node.label = token;
        if ("true".equals(token)) {
            return finish(node, new BoolV(true));
        }
        if ("false".equals(token)) {
            return finish(node, new BoolV(false));
        }
        if (isNumberToken(token)) {
            boolean integral = !token.contains(".") && !token.contains("/");
            return finish(node, NumV.of(BigRational.parse(token), integral));
        }
        if (locals.containsKey(token)) {
            return finish(node, locals.get(token));
        }
        ModelDef md = model == null ? null : model.get(token);
        if (md != null && !md.isArray()) {
            node.sourceEntryId = md.sourceEntryId();
            Value v = md.scalar();
            return finish(node, v);
        }
        SymbolInfo info = state.lookup(token);
        if (info != null && info.def != null && info.def.isConstant()) {
            FunDef def = info.def;
            Value v = eval(def.body(), locals, child(node));
            node.sourceEntryId = info.introducedAt;
            return finish(node, v);
        }
        if (md != null && md.isArray()) {
            return finish(node, md.array());
        }
        // Partial model: symbol declared but not assigned by the solver.
        Sort sort = info == null ? Sort.BOOL : info.sort;
        return unknown(node, sort, "partial model: symbol '" + token + "' is not assigned");
    }

    private Value boolUnop(SExpr e, Map<String, Value> locals, EvalTree node,
                           java.util.function.Function<Boolean, Boolean> op) {
        Value v = eval(e.arg(0), locals, child(node));
        if (v instanceof BoolV b) {
            return finish(node, new BoolV(op.apply(b.value())));
        }
        return unknown(node, Sort.BOOL, "non-Bool operand");
    }

    private Value boolNary(SExpr e, Map<String, Value> locals, EvalTree node,
                           boolean identity, java.util.function.BinaryOperator<Boolean> op) {
        boolean acc = identity;
        for (int i = 0; i < e.arity(); i++) {
            Value v = eval(e.arg(i), locals, child(node));
            if (!(v instanceof BoolV b)) {
                return unknown(node, Sort.BOOL, "non-Bool operand in '" + e.head() + "'");
            }
            acc = op.apply(acc, b.value());
        }
        return finish(node, new BoolV(acc));
    }

    private Value boolBin(SExpr e, Map<String, Value> locals, EvalTree node,
                          java.util.function.BinaryOperator<Boolean> op) {
        Value a = eval(e.arg(0), locals, child(node));
        Value b = eval(e.arg(1), locals, child(node));
        if (a instanceof BoolV x && b instanceof BoolV y) {
            return finish(node, new BoolV(op.apply(x.value(), y.value())));
        }
        return unknown(node, Sort.BOOL, "non-Bool operands");
    }

    private Value compare(SExpr e, Map<String, Value> locals, EvalTree node, int expect, boolean leq) {
        Value a = eval(e.arg(0), locals, child(node));
        Value b = eval(e.arg(1), locals, child(node));
        if (a instanceof NumV x && b instanceof NumV y) {
            int c = x.value().compareTo(y.value());
            boolean r = leq ? c <= 0 : c == expect;
            return finish(node, new BoolV(r));
        }
        if (a instanceof BoolV x && b instanceof BoolV y && "=".equals(e.head())) {
            return finish(node, new BoolV(x.value() == y.value()));
        }
        return unknown(node, Sort.BOOL, "comparison operands are not both numeric (possibly unsupported theory)");
    }

    private Value compare(SExpr e, Map<String, Value> locals, EvalTree node,
                          int expect, boolean leq, int lo, int hi) {
        Value a = eval(e.arg(0), locals, child(node));
        Value b = eval(e.arg(1), locals, child(node));
        if (a instanceof NumV x && b instanceof NumV y) {
            int c = x.value().compareTo(y.value());
            return finish(node, new BoolV(c >= lo && c <= hi));
        }
        return unknown(node, Sort.BOOL, "non-numeric comparison operands");
    }

    private Value distinct(SExpr e, Map<String, Value> locals, EvalTree node) {
        List<Value> vs = new ArrayList<>();
        for (int i = 0; i < e.arity(); i++) {
            vs.add(eval(e.arg(i), locals, child(node)));
        }
        for (int i = 0; i < vs.size(); i++) {
            for (int j = i + 1; j < vs.size(); j++) {
                if (vs.get(i).equals(vs.get(j))) {
                    return finish(node, new BoolV(false));
                }
            }
        }
        return finish(node, new BoolV(true));
    }

    private Value arithmetic(SExpr e, Map<String, Value> locals, EvalTree node) {
        String op = e.head();
        if ("abs".equals(op) && e.arity() == 1) {
            Value a = eval(e.arg(0), locals, child(node));
            if (a instanceof NumV x) {
                return finish(node, NumV.of(x.value().compareTo(BigRational.ZERO) < 0
                        ? x.value().negate() : x.value(), x.integral()));
            }
            return unknown(node, Sort.INT, "abs of non-numeric term");
        }
        boolean unaryMinus = "-".equals(op) && e.arity() == 1;
        List<Value> vs = new ArrayList<>();
        for (int i = 0; i < e.arity(); i++) {
            vs.add(eval(e.arg(i), locals, child(node)));
        }
        if (unaryMinus) {
            if (vs.get(0) instanceof NumV x) {
                return finish(node, NumV.of(x.value().negate(), x.integral()));
            }
            return unknown(node, Sort.INT, "unary minus on non-numeric term");
        }
        boolean integral = vs.stream().allMatch(v -> v instanceof NumV n && n.integral())
                && !"/".equals(op);
        BigRational acc = (vs.get(0) instanceof NumV n) ? n.value() : null;
        if (acc == null) {
            return unknown(node, Sort.INT, "non-numeric arithmetic operand");
        }
        for (int i = 1; i < vs.size(); i++) {
            if (!(vs.get(i) instanceof NumV n)) {
                return unknown(node, Sort.INT, "non-numeric arithmetic operand");
            }
            acc = switch (op) {
                case "+" -> acc.add(n.value());
                case "-" -> acc.sub(n.value());
                case "*" -> acc.mul(n.value());
                case "/" -> acc.div(n.value());
                case "div" -> BigRational.of(intDiv(acc.num, n.value().num), BigInteger.ONE);
                default -> BigRational.of(acc.num.remainder(n.value().num.abs()), BigInteger.ONE);
            };
        }
        return finish(node, NumV.of(acc, integral));
    }

    private static BigInteger intDiv(BigInteger a, BigInteger b) {
        BigInteger q = a.divide(b.abs());
        if (a.signum() * b.signum() < 0 && a.remainder(b.abs()).signum() != 0) {
            q = q.add(BigInteger.ONE);
        }
        return b.signum() < 0 ? q.negate() : q;
    }

    private Value ite(SExpr e, Map<String, Value> locals, EvalTree node) {
        Value c = eval(e.arg(0), locals, child(node));
        if (!(c instanceof BoolV b)) {
            return unknown(node, Sort.REAL, "ite condition is not Bool");
        }
        return eval(b.value() ? e.arg(1) : e.arg(2), locals, node);
    }

    private Value select(SExpr e, Map<String, Value> locals, EvalTree node) {
        Value arr = eval(e.arg(0), locals, child(node));
        Value idx = eval(e.arg(1), locals, child(node));
        if (arr instanceof ArrayV a) {
            Value cell = a.select(idx);
            if (cell instanceof UnknownV u) {
                return unknown(node, a.sort().element, u.reason());
            }
            return finish(node, cell);
        }
        return unknown(node, Sort.REAL, "select on a non-array value");
    }

    private Value annotated(SExpr e, Map<String, Value> locals, EvalTree node) {
        return eval(e.arg(0), locals, node);
    }

    private Value application(SExpr e, Map<String, Value> locals, EvalTree node, String name) {
        SymbolInfo info = state.lookup(name);
        if (info == null) {
            return unsupported(node, Sort.BOOL,
                    "unknown function '" + name + "' (theory not supported in local subset)");
        }
        if (info.def == null) {
            return unsupported(node, info.sort,
                    "uninterpreted function '" + name + "' cannot be evaluated locally");
        }
        FunDef def = info.def;
        if (def.params().size() != e.arity()) {
            return unknown(node, info.sort, "arity mismatch applying '" + name + "'");
        }
        Map<String, Value> call = new LinkedHashMap<>();
        for (int i = 0; i < e.arity(); i++) {
            Value arg = eval(e.arg(i), locals, child(node));
            call.put(def.params().get(i).name(), arg);
        }
        return eval(def.body(), call, node);
    }

    private Value unsupported(EvalTree node, Sort sort, String reason) {
        if (!unsupportedMarks.contains(reason)) {
            unsupportedMarks.add(reason);
        }
        return unknown(node, sort, reason);
    }

    private Value unknown(EvalTree node, Sort sort, String reason) {
        node.status = EvalTree.Status.UNKNOWN;
        node.reason = reason;
        node.sortText = sort.name();
        node.valueText = "#unknown";
        diagnostics.add(reason);
        return new UnknownV(sort, reason);
    }

    private Value finish(EvalTree node, Value v) {
        if (v instanceof UnknownV u) {
            node.status = EvalTree.Status.UNKNOWN;
            node.reason = u.reason();
        }
        node.sortText = v.sort().name();
        node.valueText = Values.show(v);
        return v;
    }

    private EvalTree child(EvalTree parent) {
        EvalTree c = new EvalTree();
        parent.children.add(c);
        return c;
    }

    private static boolean isNumberToken(String t) {
        try {
            BigRational.parse(t);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
