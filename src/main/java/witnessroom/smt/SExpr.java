package witnessroom.smt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable S-expression: either an atom (symbol / numeral / decimal / string token)
 * or a list of children. Raw token spelling is preserved for the audit trail.
 */
public sealed interface SExpr permits SExpr.Atom, SExpr.List {

    String raw();

    boolean isList();

    List<SExpr> children();

    record Atom(String raw) implements SExpr {
        @Override public boolean isList() { return false; }
        @Override public List<SExpr> children() { return Collections.emptyList(); }
        @Override public String toString() { return raw; }
    }

    record List(List<SExpr> children) implements SExpr {
        public List { children = Collections.unmodifiableList(new ArrayList<>(children)); }
        @Override public String raw() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(children.get(i).raw());
            }
            return sb.append(')').toString();
        }
        @Override public boolean isList() { return true; }
        @Override public String toString() { return raw(); }

        public SExpr head() { return children.get(0); }
        public String headSym() { return children.get(0) instanceof Atom a ? a.raw() : null; }
        public List<SExpr> args() { return children.subList(1, children.size()); }
    }
}
