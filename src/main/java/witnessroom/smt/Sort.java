package witnessroom.smt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Sort in the locally supported finite fragment. */
public sealed interface Sort permits Sort.AtomSort, Sort.ArraySort, Sort.Unsupported {

    String name();

    record AtomSort(String name) implements Sort {
        public static final AtomSort BOOL = new AtomSort("Bool");
        public static final AtomSort INT = new AtomSort("Int");
        public static final AtomSort REAL = new AtomSort("Real");
    }

    record ArraySort(Sort index, Sort element) implements Sort {
        @Override public String name() { return "(Array " + index.name() + " " + element.name() + ")"; }
    }

    /** Sort outside the verified fragment (BitVec, FP, Reals, RoundingMode, ...). */
    record Unsupported(String raw, String reason) implements Sort {
        @Override public String name() { return raw; }
    }

    static Sort of(SExpr sexpr) {
        return of(sexpr, new ArrayList<>());
    }

    static Sort of(SExpr sexpr, List<Unsupported> unsupported) {
        if (sexpr instanceof SExpr.Atom a) {
            return switch (a.raw()) {
                case "Bool" -> AtomSort.BOOL;
                case "Int" -> AtomSort.INT;
                case "Real" -> AtomSort.REAL;
                default -> {
                    Unsupported u = new Unsupported(a.raw(),
                            "atomic sort '" + a.raw() + "' is not in the local finite fragment");
                    unsupported.add(u);
                    yield u;
                }
            };
        }
        SExpr.List l = (SExpr.List) sexpr;
        if (l.children().size() == 3 && "Array".equals(l.headSym())) {
            return new ArraySort(of(l.args().get(0), unsupported), of(l.args().get(1), unsupported));
        }
        // Indexed sorts such as (_ BitVec 32) or (_ FloatingPoint 8 24).
        if (l.children().size() >= 2 && "_".equals(l.headSym())
                && l.args().get(0) instanceof SExpr.Atom name) {
            Unsupported u = new Unsupported(l.raw(),
                    "indexed/parametric sort '" + name.raw() + "' is unsupported locally");
            unsupported.add(u);
            return u;
        }
        Unsupported u = new Unsupported(l.raw(), "compound sort is unsupported locally: " + l.raw());
        unsupported.add(u);
        return u;
    }

    static boolean supported(Sort s) {
        if (s instanceof Unsupported) return false;
        if (s instanceof ArraySort a) return supported(a.index()) && supported(a.element());
        return true;
    }

    static String key(Sort s) {
        if (s instanceof AtomSort a) return a.name();
        if (s instanceof ArraySort a) return "A[" + key(a.index()) + "," + key(a.element()) + "]";
        if (s instanceof Unsupported u) return "U:" + u.raw();
        throw new IllegalStateException(Objects.toString(s));
    }
}
