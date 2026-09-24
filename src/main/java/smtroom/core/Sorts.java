package smtroom.core;

import smtroom.sexpr.SExpr;

/** Sort support in the local finite semantic subset. */
public final class Sorts {
    private Sorts() {}

    public static boolean isSupported(SExpr sort) {
        if (sort instanceof SExpr.Atom a) {
            return switch (a.text()) {
                case "Bool", "Int", "Real" -> true;
                default -> false; // String, (_ BitVec n), Float*, uninterpreted sorts...
            };
        }
        if (sort instanceof SExpr.ListExpr l && l.items().size() == 3
                && l.items().get(0) instanceof SExpr.Atom h && h.text().equals("Array")) {
            return isSupported(l.items().get(1)) && isSupported(l.items().get(2));
        }
        return false;
    }

    public static boolean allSupported(SExpr sortsList) {
        if (sortsList instanceof SExpr.ListExpr l) {
            for (SExpr s : l.items()) if (!isSupported(s)) return false;
            return true;
        }
        return false;
    }

    /** Theory classification for diagnostics. */
    public static String theoryOf(SExpr sort) {
        String s = SExpr.print(sort);
        if (s.contains("BitVec")) return "bitvectors";
        if (s.equals("String") || s.startsWith("(Seq")) return "strings";
        if (s.contains("Float") || s.contains("RoundingMode")) return "floating-point";
        if (s.startsWith("Array")) return "arrays";
        if (s.equals("Int") || s.equals("Real") || s.equals("Bool")) return "arith";
        return "uninterpreted-sort:" + s;
    }
}
