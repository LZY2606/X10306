package smtwitness.smtlib;

import java.util.Objects;

/** SMT-LIB sort in the local subset: a named sort or (Array index element). */
public final class Sort {
    private final String name;
    private final Sort index;
    private final Sort element;

    private Sort(String name, Sort index, Sort element) {
        this.name = name;
        this.index = index;
        this.element = element;
    }

    public static Sort named(String name) {
        return new Sort(name, null, null);
    }

    public static Sort array(Sort index, Sort element) {
        return new Sort("Array", index, element);
    }

    public static Sort fromExpr(SExpr e) {
        if (e instanceof SExpr.Atom a) {
            return named(a.raw());
        }
        SExpr.List l = (SExpr.List) e;
        if ("Array".equals(l.head()) && l.size() == 3) {
            return array(fromExpr(l.get(1)), fromExpr(l.get(2)));
        }
        throw new SmtParseException("unsupported sort form: " + e);
    }

    public String name() {
        return name;
    }

    public Sort index() {
        return index;
    }

    public Sort element() {
        return element;
    }

    public boolean isArray() {
        return index != null;
    }

    /** Coordinate basis tag used when exporting domain quantities. */
    public String basis() {
        return isArray() ? "(Array " + index.basis() + " " + element.basis() + ")" : name;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Sort s)) {
            return false;
        }
        return Objects.equals(name, s.name) && Objects.equals(index, s.index) && Objects.equals(element, s.element);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, index, element);
    }

    @Override
    public String toString() {
        return basis();
    }
}
