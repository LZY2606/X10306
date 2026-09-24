package witness.smt;

import java.util.ArrayList;
import java.util.List;

/** S-expression: either an atom or a list. Canonical toString for fingerprints. */
public final class SExp {
    public final String atom;
    public final List<SExp> list;

    private SExp(String atom, List<SExp> list) {
        this.atom = atom;
        this.list = list;
    }

    public static SExp atom(String s) { return new SExp(s, null); }
    public static SExp list(List<SExp> items) { return new SExp(null, List.copyOf(items)); }

    public boolean isAtom() { return atom != null; }
    public boolean isList() { return list != null; }

    public SExp get(int i) { return list.get(i); }
    public int size() { return list.size(); }
    public String head() { return isList() && !list.isEmpty() && list.get(0).isAtom() ? list.get(0).atom : null; }

    public boolean isAtom(String s) { return s.equals(atom); }

    @Override public String toString() {
        if (isAtom()) return atom;
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(list.get(i));
        }
        return sb.append(')').toString();
    }

    public static SExp of(String head, SExp... args) {
        List<SExp> items = new ArrayList<>();
        items.add(atom(head));
        for (SExp a : args) items.add(a);
        return list(items);
    }
}
