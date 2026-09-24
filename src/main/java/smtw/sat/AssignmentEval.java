package smtw.sat;

import smtw.model.Val;
import smtw.smt.Sexpr;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates terms against a concrete assignment. Mirrors the subset semantics of
 * {@link smtw.model.Evaluator} but has no model: symbols resolve from the assignment.
 * Returns Val.UnsupportedV for constructs outside the local search subset.
 */
final class AssignmentEval {

    private AssignmentEval() {}

    static Val eval(Sexpr term, Map<String, Val> env) {
        if (term instanceof Sexpr.Atom a) {
            String t = a.token();
            if ("true".equals(t)) return Val.BoolV.TRUE;
            if ("false".equals(t)) return Val.BoolV.FALSE;
            if (t.matches("-?\\d+")) return new Val.IntV(new BigInteger(t));
            if (env.containsKey(t)) return env.get(t);
            if (t.matches("-?\\d+\\.\\d+"))
                return new Val.UnsupportedV("real literal '" + t + "' cannot be searched locally");
            return new Val.UnsupportedV("free symbol '" + t + "' has no searchable finite domain");
        }
        Sexpr.List l = (Sexpr.List) term;
        String head = l.items().get(0) instanceof Sexpr.Atom h ? h.token() : "";
        if ("!".equals(head)) return eval(l.items().get(1), env);
        if ("as".equals(head) && l.items().size() == 3
                && l.items().get(1) instanceof Sexpr.Atom c && "const".equals(c.token())) {
            Val d = eval(l.items().get(2), env);
            return new Val.ArrV(d, List.of());
        }
        if ("ite".equals(head)) {
            Val c = eval(l.items().get(1), env);
            if (c instanceof Val.BoolV b) return eval(l.items().get(b.b() ? 2 : 3), env);
            return c;
        }
        List<Val> vs = new ArrayList<>();
        for (int i = 1; i < l.items().size(); i++) vs.add(eval(l.items().get(i), env));
        for (Val v : vs) if (v instanceof Val.UnsupportedV) return v;
        return apply(head, vs);
    }

    private static Val apply(String head, List<Val> vs) {
        if ("select".equals(head)) {
            Val a = vs.get(0);
            if (a instanceof Val.ArrV arr) return arr.select(vs.get(1));
            return new Val.UnsupportedV("select on non-constant array");
        }
        if ("store".equals(head)) {
            Val a = vs.get(0);
            if (a instanceof Val.ArrV arr) {
                List<Val.ArrV.Store> st = new ArrayList<>(arr.stores());
                st.add(new Val.ArrV.Store(vs.get(1), vs.get(2)));
                return new Val.ArrV(arr.defaultValue(), st);
            }
            return new Val.UnsupportedV("store on search variable array");
        }
        for (Val v : vs) {
            if (v instanceof Val.UndefV || v instanceof Val.ErrorV) return v;
        }
        return switch (head) {
            case "and" -> Val.BoolV.of(vs.stream().allMatch(v -> ((Val.BoolV) v).b()));
            case "or" -> Val.BoolV.of(vs.stream().anyMatch(v -> ((Val.BoolV) v).b()));
            case "not" -> Val.BoolV.of(!((Val.BoolV) vs.get(0)).b());
            case "xor" -> Val.BoolV.of(((Val.BoolV) vs.get(0)).b() ^ ((Val.BoolV) vs.get(1)).b());
            case "=>" -> Val.BoolV.of(!((Val.BoolV) vs.get(0)).b() || ((Val.BoolV) vs.get(1)).b());
            case "=" -> Val.BoolV.of(Val.valsEqual(vs.get(0), vs.get(1)));
            case "distinct" -> Val.BoolV.of(!Val.valsEqual(vs.get(0), vs.get(1)));
            case "<" -> Val.BoolV.of(cmp(vs) < 0);
            case "<=" -> Val.BoolV.of(cmp(vs) <= 0);
            case ">" -> Val.BoolV.of(cmp(vs) > 0);
            case ">=" -> Val.BoolV.of(cmp(vs) >= 0);
            case "+" -> intBin(vs, BigInteger::add);
            case "-" -> vs.size() == 1
                    ? new Val.IntV(((Val.IntV) vs.get(0)).v().negate())
                    : intBin(vs, BigInteger::subtract);
            case "*" -> intBin(vs, BigInteger::multiply);
            case "div" -> new Val.IntV(((Val.IntV) vs.get(0)).v().divide(((Val.IntV) vs.get(1)).v()));
            case "mod" -> new Val.IntV(((Val.IntV) vs.get(0)).v().mod(((Val.IntV) vs.get(1)).v().abs()));
            default -> new Val.UnsupportedV("search symbol '" + head + "' outside local subset");
        };
    }

    private static int cmp(List<Val> vs) {
        BigInteger a = ((Val.IntV) vs.get(0)).v();
        BigInteger b = ((Val.IntV) vs.get(1)).v();
        return a.compareTo(b);
    }

    private static Val intBin(List<Val> vs, java.util.function.BinaryOperator<BigInteger> op) {
        BigInteger acc = ((Val.IntV) vs.get(0)).v();
        for (int i = 1; i < vs.size(); i++) acc = op.apply(acc, ((Val.IntV) vs.get(i)).v());
        return new Val.IntV(acc);
    }
}
