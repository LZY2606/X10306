package room.testimony;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Local finite semantic subset: Int, Bool, (Array Int Int) with const/store/select,
 * boolean connectives, linear arithmetic comparisons, +,-,*, ite, =, distinct.
 * Anything outside the subset is reported as UNSUPPORTED with the theory named;
 * missing model entries are reported as UNKNOWN with the missing symbols listed.
 */
public final class Terms {
    private Terms() {}

    public sealed interface Val {
        record IntV(BigInteger v) implements Val {}
        record BoolV(boolean v) implements Val {}
        record ArrV(BigInteger def, TreeMap<BigInteger, BigInteger> stores) implements Val {}
    }

    public enum Status { OK, UNKNOWN, UNSUPPORTED }

    public record Ev(Status status, Val val, String detail, Set<String> missing) {
        public static Ev ok(Val v) {
            return new Ev(Status.OK, v, "", Set.of());
        }

        public static Ev unknown(String detail, Set<String> missing) {
            return new Ev(Status.UNKNOWN, null, detail, Set.copyOf(missing));
        }

        public static Ev unsupported(String theory) {
            return new Ev(Status.UNSUPPORTED, null, theory, Set.of());
        }

        public boolean isFalse() {
            return status == Status.OK && val instanceof Val.BoolV b && !b.v();
        }

        public boolean isTrue() {
            return status == Status.OK && val instanceof Val.BoolV b && b.v();
        }
    }

    public record Trace(String expr, String status, String value, List<Trace> children) {}

    public record Evaluated(Ev ev, Trace trace) {}

    public static String render(Val v) {
        if (v instanceof Val.IntV i) return i.v().toString();
        if (v instanceof Val.BoolV b) return Boolean.toString(b.v());
        if (v instanceof Val.ArrV a) {
            StringBuilder b = new StringBuilder("array(default=").append(a.def());
            if (!a.stores().isEmpty()) {
                b.append(", stores={");
                a.stores().forEach((k, val) -> b.append(k).append("->").append(val).append(" "));
                b.setLength(b.length() - 1);
                b.append("}");
            }
            return b.append(")").toString();
        }
        return "?";
    }

    public static Evaluated eval(Sexp t, Map<String, Val> env, Map<String, String> sorts) {
        return eval0(t, env, sorts);
    }

    private static Evaluated done(String label, Ev ev, List<Evaluated> children) {
        List<Trace> ct = new ArrayList<>();
        for (Evaluated c : children) ct.add(c.trace());
        String value = ev.status() == Status.OK ? render(ev.val()) : ev.detail();
        return new Evaluated(ev, new Trace(label, ev.status().name(), value, ct));
    }

    private static String label(Sexp t) {
        String s = Sexp.str(t);
        return s.length() > 48 ? s.substring(0, 45) + "..." : s;
    }

    private static Evaluated eval0(Sexp t, Map<String, Val> env, Map<String, String> sorts) {
        String lbl = label(t);
        if (t.isAtom()) {
            String a = t.atom();
            switch (a) {
                case "true" -> { return done(lbl, Ev.ok(new Val.BoolV(true)), List.of()); }
                case "false" -> { return done(lbl, Ev.ok(new Val.BoolV(false)), List.of()); }
                default -> {}
            }
            if (a.matches("-?\\d+")) return done(lbl, Ev.ok(new Val.IntV(new BigInteger(a))), List.of());
            if (a.matches("\\d+\\.\\d+")) {
                return done(lbl, Ev.unsupported("sort:Real literal '" + a + "' kept raw; not convertible in Int domain"), List.of());
            }
            if (a.startsWith("#x") || a.startsWith("#b")) {
                return done(lbl, Ev.unsupported("theory:FixedSizeBitVectors literal '" + a + "'"), List.of());
            }
            if (a.startsWith("\"")) {
                return done(lbl, Ev.unsupported("theory:Strings literal"), List.of());
            }
            Val v = env.get(a);
            if (v != null) return done(lbl, Ev.ok(v), List.of());
            String sort = sorts.get(a);
            if (sort != null && !Script.supportedSort(sort)) {
                return done(lbl, Ev.unsupported("theory/sort of '" + a + "': " + sort), List.of());
            }
            return done(lbl, Ev.unknown("no model entry for '" + a + "'" + (sort == null ? " (undeclared)" : ""),
                    Set.of(a)), List.of());
        }

        List<Sexp> items = t.items();
        Sexp head = t.head();
        String op = t.headAtom();

        if (head instanceof Sexp.ListNode h && "as".equals(h.headAtom())) {
            // ((as const (Array Int Int)) default)
            if (items.size() == 2) {
                Evaluated d = eval0(items.get(1), env, sorts);
                if (d.ev().status() == Status.OK && d.ev().val() instanceof Val.IntV iv) {
                    return done(lbl, Ev.ok(new Val.ArrV(iv.v(), new TreeMap<>())), List.of(d));
                }
                return done(lbl, d.ev(), List.of(d));
            }
            return done(lbl, Ev.unsupported("as-term arity"), List.of());
        }
        if (op == null) return done(lbl, Ev.unsupported("empty or non-operator form"), List.of());

        switch (op) {
            case "!" -> {
                if (items.size() >= 2) return eval0(items.get(1), env, sorts);
                return done(lbl, Ev.unsupported("attribute form"), List.of());
            }
            case "and", "or" -> {
                boolean isAnd = op.equals("and");
                List<Evaluated> cs = new ArrayList<>();
                for (int i = 1; i < items.size(); i++) cs.add(eval0(items.get(i), env, sorts));
                for (Evaluated c : cs) {
                    if (c.ev().status() == Status.OK && c.ev().val() instanceof Val.BoolV b && b.v() != isAnd) {
                        return done(lbl, Ev.ok(new Val.BoolV(!isAnd)), cs);
                    }
                }
                Ev m = merge(cs);
                if (m != null) return done(lbl, m, cs);
                return done(lbl, Ev.ok(new Val.BoolV(isAnd)), cs);
            }
            case "not" -> {
                Evaluated c = eval0(items.get(1), env, sorts);
                if (c.ev().status() == Status.OK && c.ev().val() instanceof Val.BoolV b) {
                    return done(lbl, Ev.ok(new Val.BoolV(!b.v())), List.of(c));
                }
                return done(lbl, c.ev(), List.of(c));
            }
            case "=>" -> {
                // right associative chain; evaluate as (or (not a1) ... an)
                List<Evaluated> cs = new ArrayList<>();
                for (int i = 1; i < items.size(); i++) cs.add(eval0(items.get(i), env, sorts));
                Evaluated last = cs.get(cs.size() - 1);
                for (int i = 0; i + 1 < cs.size(); i++) {
                    if (cs.get(i).ev().isFalse()) return done(lbl, Ev.ok(new Val.BoolV(true)), cs);
                }
                if (last.ev().isTrue()) return done(lbl, Ev.ok(new Val.BoolV(true)), cs);
                Ev m = merge(cs);
                if (m != null) return done(lbl, m, cs);
                return done(lbl, Ev.ok(new Val.BoolV(false)), cs);
            }
            case "=", "distinct" -> {
                List<Evaluated> cs = new ArrayList<>();
                for (int i = 1; i < items.size(); i++) cs.add(eval0(items.get(i), env, sorts));
                Ev m = merge(cs);
                if (m != null) return done(lbl, m, cs);
                boolean eq = op.equals("=");
                boolean result = true;
                for (int i = 0; i + 1 < cs.size(); i++) {
                    boolean same = cs.get(i).ev().val().equals(cs.get(i + 1).ev().val());
                    if (eq && !same) { result = false; break; }
                    if (!eq) {
                        for (int j = i + 1; j < cs.size(); j++) {
                            if (cs.get(i).ev().val().equals(cs.get(j).ev().val())) { result = false; break; }
                        }
                        if (!result) break;
                    }
                }
                return done(lbl, Ev.ok(new Val.BoolV(result)), cs);
            }
            case "<", "<=", ">", ">=" -> {
                List<Evaluated> cs = new ArrayList<>();
                for (int i = 1; i < items.size(); i++) cs.add(eval0(items.get(i), env, sorts));
                Ev m = merge(cs);
                if (m != null) return done(lbl, m, cs);
                boolean result = true;
                for (int i = 0; i + 1 < cs.size(); i++) {
                    if (!(cs.get(i).ev().val() instanceof Val.IntV x) || !(cs.get(i + 1).ev().val() instanceof Val.IntV y)) {
                        return done(lbl, Ev.unsupported("comparison on non-Int"), cs);
                    }
                    int cmp = x.v().compareTo(y.v());
                    boolean r = switch (op) {
                        case "<" -> cmp < 0;
                        case "<=" -> cmp <= 0;
                        case ">" -> cmp > 0;
                        default -> cmp >= 0;
                    };
                    if (!r) { result = false; break; }
                }
                return done(lbl, Ev.ok(new Val.BoolV(result)), cs);
            }
            case "+", "-", "*" -> {
                List<Evaluated> cs = new ArrayList<>();
                for (int i = 1; i < items.size(); i++) cs.add(eval0(items.get(i), env, sorts));
                Ev m = merge(cs);
                if (m != null) return done(lbl, m, cs);
                BigInteger acc = switch (op) {
                    case "+" -> BigInteger.ZERO;
                    case "*" -> BigInteger.ONE;
                    default -> null;
                };
                if (op.equals("-")) {
                    if (cs.size() == 1) {
                        Val.IntV x = (Val.IntV) cs.get(0).ev().val();
                        return done(lbl, Ev.ok(new Val.IntV(x.v().negate())), cs);
                    }
                    acc = ((Val.IntV) cs.get(0).ev().val()).v();
                    for (int i = 1; i < cs.size(); i++) acc = acc.subtract(((Val.IntV) cs.get(i).ev().val()).v());
                    return done(lbl, Ev.ok(new Val.IntV(acc)), cs);
                }
                for (Evaluated c : cs) {
                    if (!(c.ev().val() instanceof Val.IntV x)) return done(lbl, Ev.unsupported("arithmetic on non-Int"), cs);
                    acc = op.equals("+") ? acc.add(x.v()) : acc.multiply(x.v());
                }
                return done(lbl, Ev.ok(new Val.IntV(acc)), cs);
            }
            case "ite" -> {
                Evaluated c = eval0(items.get(1), env, sorts);
                if (c.ev().isTrue()) {
                    Evaluated tv = eval0(items.get(2), env, sorts);
                    return done(lbl, tv.ev(), List.of(c, tv));
                }
                if (c.ev().isFalse()) {
                    Evaluated ev = eval0(items.get(3), env, sorts);
                    return done(lbl, ev.ev(), List.of(c, ev));
                }
                return done(lbl, c.ev(), List.of(c));
            }
            case "select" -> {
                Evaluated arr = eval0(items.get(1), env, sorts);
                Evaluated idx = eval0(items.get(2), env, sorts);
                Ev m = merge(List.of(arr, idx));
                if (m != null) return done(lbl, m, List.of(arr, idx));
                if (arr.ev().val() instanceof Val.ArrV a && idx.ev().val() instanceof Val.IntV i) {
                    BigInteger v = a.stores().getOrDefault(i.v(), a.def());
                    return done(lbl, Ev.ok(new Val.IntV(v)), List.of(arr, idx));
                }
                return done(lbl, Ev.unsupported("select on non-array"), List.of(arr, idx));
            }
            case "store" -> {
                Evaluated arr = eval0(items.get(1), env, sorts);
                Evaluated idx = eval0(items.get(2), env, sorts);
                Evaluated val = eval0(items.get(3), env, sorts);
                Ev m = merge(List.of(arr, idx, val));
                if (m != null) return done(lbl, m, List.of(arr, idx, val));
                if (arr.ev().val() instanceof Val.ArrV a && idx.ev().val() instanceof Val.IntV i
                        && val.ev().val() instanceof Val.IntV vv) {
                    TreeMap<BigInteger, BigInteger> copy = new TreeMap<>(a.stores());
                    copy.put(i.v(), vv.v());
                    return done(lbl, Ev.ok(new Val.ArrV(a.def(), copy)), List.of(arr, idx, val));
                }
                return done(lbl, Ev.unsupported("store on non-array"), List.of(arr, idx, val));
            }
            case "forall", "exists" -> {
                return done(lbl, Ev.unsupported("quantifiers outside local subset"), List.of());
            }
            case "let" -> {
                return done(lbl, Ev.unsupported("let-bindings outside local subset"), List.of());
            }
            case "div", "mod", "abs", "/", "to_real", "to_int", "is_int" -> {
                return done(lbl, Ev.unsupported("operator:" + op + " outside local subset"), List.of());
            }
            default -> {
                if (op.startsWith("bv") || op.equals("concat") || op.equals("extract")) {
                    return done(lbl, Ev.unsupported("theory:FixedSizeBitVectors operator:" + op), List.of());
                }
                if (op.startsWith("str.")) {
                    return done(lbl, Ev.unsupported("theory:Strings operator:" + op), List.of());
                }
                return done(lbl, Ev.unsupported("operator:" + op + " outside local subset"), List.of());
            }
        }
    }

    private static Ev merge(List<Evaluated> cs) {
        Set<String> missing = new LinkedHashSet<>();
        StringBuilder unknownDetail = new StringBuilder();
        for (Evaluated c : cs) {
            if (c.ev().status() == Status.UNSUPPORTED) return c.ev();
        }
        for (Evaluated c : cs) {
            if (c.ev().status() == Status.UNKNOWN) {
                missing.addAll(c.ev().missing());
                if (unknownDetail.length() > 0) unknownDetail.append("; ");
                unknownDetail.append(c.ev().detail());
            }
        }
        if (!missing.isEmpty() || unknownDetail.length() > 0) {
            return Ev.unknown(unknownDetail.toString(), missing);
        }
        return null;
    }
}
