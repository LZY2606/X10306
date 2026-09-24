package smtw.smt;

import java.util.List;

/**
 * Sort expressions of the local subset. Bool/Int/Real are finite primitives;
 * Array sorts carry index/element sorts; uninterpreted sorts are declared symbols.
 */
public sealed interface Sort permits Sort.Prim, Sort.Decl, Sort.App {

    String sortName();

    enum Prim implements Sort { BOOL("Bool"), INT("Int"), REAL("Real");
        private final String n;
        Prim(String n) { this.n = n; }
        @Override public String sortName() { return n; }
    }

    /** Declared uninterpreted sort, e.g. (declare-sort Color 0). */
    record Decl(String sortName, int arity) implements Sort {}

    /** Parameterised application; (Array X Y) is the only meaningful one locally. */
    record App(String sortName, List<Sort> args) implements Sort {
        public App { args = List.copyOf(args); }
    }

    static Sort parse(Sexpr s) {
        if (s instanceof Sexpr.Atom a) {
            return switch (a.token()) {
                case "Bool" -> Prim.BOOL;
                case "Int" -> Prim.INT;
                case "Real" -> Prim.REAL;
                default -> new Decl(a.token(), 0);
            };
        }
        Sexpr.List l = (Sexpr.List) s;
        String head = Sexpr.token(l.items().get(0));
        List<Sort> args = l.args().stream().map(Sort::parse).toList();
        if ("Array".equals(head)) {
            return new App("Array", args);
        }
        return new App(head, args);
    }

    default boolean isNumeric() { return this == Prim.INT || this == Prim.REAL; }
}
