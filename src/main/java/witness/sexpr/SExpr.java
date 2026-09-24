package witness.sexpr;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable S-expression node. {@code atom} is null for lists.
 * {@code comments} carries "; ..." lines encountered immediately before the node,
 * so domain annotations such as "; @unit meter x" survive import verbatim.
 */
public final class SExpr {
    private final String atom;
    private final List<SExpr> children;
    private final int line;
    private final int col;
    private final String comments;

    public SExpr(String atom, List<SExpr> children, int line, int col, String comments) {
        this.atom = atom;
        this.children = children;
        this.line = line;
        this.col = col;
        this.comments = comments;
    }

    public static SExpr atom(String text, int line, int col, String comments) {
        return new SExpr(text, null, line, col, comments);
    }

    public static SExpr list(List<SExpr> children, int line, int col, String comments) {
        return new SExpr(null, children, line, col, comments);
    }

    public boolean isList() { return children != null; }
    public boolean isAtom() { return children == null; }
    public String atom() { return atom; }
    public List<SExpr> children() { return children; }
    public int line() { return line; }
    public int col() { return col; }
    public String comments() { return comments == null ? "" : comments; }

    public String head() {
        if (children == null || children.isEmpty()) return null;
        return children.get(0).atom;
    }

    public SExpr get(int i) { return children.get(i); }
    public int size() { return children == null ? 0 : children.size(); }

    public List<String> atoms() {
        List<String> out = new ArrayList<>();
        if (children != null) for (SExpr c : children) out.add(c.isAtom() ? c.atom : c.toString());
        return out;
    }

    @Override
    public String toString() {
        if (atom != null) return atom;
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(children.get(i).toString());
        }
        return sb.append(')').toString();
    }
}
