package smtarchive.smt;

import java.util.List;

/** SMT-LIB sorts in the locally supported fragment plus an explicit UNSUPPORTED marker. */
public record Sort(String name, List<Sort> params) {
    public static final Sort BOOL = new Sort("Bool", List.of());
    public static final Sort INT = new Sort("Int", List.of());
    public static final Sort REAL = new Sort("Real", List.of());
    public static final Sort UNKNOWN = new Sort("?", List.of());

    public static Sort simple(String n) { return new Sort(n, List.of()); }
    public static Sort array(Sort idx, Sort elem) { return new Sort("Array", List.of(idx, elem)); }

    public boolean isArith() { return name.equals("Int") || name.equals("Real"); }
    public boolean isUnsupported() { return !name.equals("Bool") && !isArith() && !name.equals("Array") && !name.equals("?"); }

    public static Sort fromSExpr(SExpr e) {
        if (e instanceof SExpr.Atom a) return simple(SExpr.unquote(a.token()));
        if (e instanceof SExpr.List l) {
            String h = l.headToken();
            if (("Array".equals(h) || "_".equals(h) && l.items().size() >= 4)) {
                if ("Array".equals(h) && l.items().size() == 3)
                    return array(fromSExpr(l.items().get(1)), fromSExpr(l.items().get(2)));
            }
            // parameterized / indexed sorts such as (_ BitVec 32) stay as a named marker
            StringBuilder sb = new StringBuilder(h == null ? "?" : h);
            for (int i = 1; i < l.items().size(); i++) {
                SExpr it = l.items().get(i);
                sb.append(' ').append(it instanceof SExpr.Atom a ? SExpr.unquote(a.token()) : fromSExpr(it));
            }
            return simple(sb.toString());
        }
        return UNKNOWN;
    }

    @Override public String toString() {
        return params.isEmpty() ? name : "(" + name + params.stream().map(Sort::toString).reduce("", (a, b) -> a + " " + b) + ")";
    }
}
