package chamber.sexpr;

import java.util.List;
import java.util.stream.Collectors;

/** S-expression: the raw representation for all imported SMT-LIB material. */
public sealed interface Sexpr {

    record Atom(String text) implements Sexpr {
        @Override public String toString() {
            boolean bare = text.matches("[A-Za-z0-9_+\\-*/<>=.$!~^&|?%:@#]+") && !text.isEmpty();
            return bare ? text : "|" + text + "|";
        }
    }

    record SList(List<Sexpr> items) implements Sexpr {
        @Override public String toString() {
            return items.stream().map(Sexpr::toString).collect(Collectors.joining(" ", "(", ")"));
        }
    }

    static Sexpr atom(String text) {
        return new Atom(text);
    }

    static Sexpr list(Sexpr... items) {
        return new SList(List.of(items));
    }

    default boolean isAtom() {
        return this instanceof Atom;
    }

    default String asAtom() {
        if (this instanceof Atom a) return a.text();
        throw new IllegalStateException("expected atom, got list: " + this);
    }

    default List<Sexpr> asList() {
        if (this instanceof SList l) return l.items();
        throw new IllegalStateException("expected list, got atom: " + this);
    }
}
