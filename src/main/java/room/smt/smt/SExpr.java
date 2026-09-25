package room.smt.smt;

import java.util.ArrayList;
import java.util.List;

/** S-expression: atom or list. Immutable. */
public final class SExpr {
    public final String atom;          // non-null when atom
    public final List<SExpr> children; // non-null when list
    public final String text;          // original source text (normalized slice)

    private SExpr(String atom, List<SExpr> children, String text) {
        this.atom = atom;
        this.children = children;
        this.text = text;
    }

    public static SExpr atom(String a) { return new SExpr(a, null, a); }
    public static SExpr list(List<SExpr> c, String text) { return new SExpr(null, List.copyOf(c), text); }

    public boolean isAtom() { return atom != null; }
    public boolean isList() { return children != null; }
    public String head() {
        if (children == null || children.isEmpty()) return null;
        SExpr h = children.get(0);
        return h.isAtom() ? h.atom : null;
    }
    public SExpr get(int i) { return children.get(i); }
    public int size() { return children == null ? 0 : children.size(); }

    @Override public String toString() { return text; }

    /** Parse all top-level s-expressions from a source string. */
    public static List<SExpr> parseAll(String src) {
        List<SExpr> out = new ArrayList<>();
        int[] pos = {0};
        while (true) {
            skipWs(src, pos);
            if (pos[0] >= src.length()) break;
            out.add(parseOne(src, pos));
        }
        return out;
    }

    public static SExpr parse(String src) {
        List<SExpr> all = parseAll(src);
        if (all.size() != 1) throw new ParseException("expected exactly one s-expression, got " + all.size());
        return all.get(0);
    }

    private static void skipWs(String s, int[] pos) {
        while (pos[0] < s.length()) {
            char c = s.charAt(pos[0]);
            if (Character.isWhitespace(c)) { pos[0]++; continue; }
            if (c == ';') { while (pos[0] < s.length() && s.charAt(pos[0]) != '\n') pos[0]++; continue; }
            break;
        }
    }

    private static SExpr parseOne(String s, int[] pos) {
        skipWs(s, pos);
        if (pos[0] >= s.length()) throw new ParseException("unexpected end of input");
        int start = pos[0];
        char c = s.charAt(pos[0]);
        if (c == '(') {
            pos[0]++;
            List<SExpr> kids = new ArrayList<>();
            while (true) {
                skipWs(s, pos);
                if (pos[0] >= s.length()) throw new ParseException("unclosed '(' at offset " + start);
                if (s.charAt(pos[0]) == ')') { pos[0]++; break; }
                kids.add(parseOne(s, pos));
            }
            return list(kids, s.substring(start, pos[0]));
        }
        if (c == ')') throw new ParseException("unexpected ')' at offset " + pos[0]);
        if (c == '|') { // quoted symbol
            pos[0]++;
            StringBuilder sb = new StringBuilder("|");
            while (pos[0] < s.length() && s.charAt(pos[0]) != '|') { sb.append(s.charAt(pos[0])); pos[0]++; }
            if (pos[0] >= s.length()) throw new ParseException("unclosed quoted symbol at offset " + start);
            sb.append('|'); pos[0]++;
            return atom(sb.toString());
        }
        if (c == '"') { // string literal
            pos[0]++;
            StringBuilder sb = new StringBuilder("\"");
            while (pos[0] < s.length()) {
                char d = s.charAt(pos[0]);
                sb.append(d); pos[0]++;
                if (d == '"') {
                    if (pos[0] < s.length() && s.charAt(pos[0]) == '"') { sb.append('"'); pos[0]++; continue; }
                    return atom(sb.toString());
                }
            }
            throw new ParseException("unclosed string literal at offset " + start);
        }
        // plain atom
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length()) {
            char d = s.charAt(pos[0]);
            if (Character.isWhitespace(d) || d == '(' || d == ')' || d == ';') break;
            sb.append(d); pos[0]++;
        }
        if (sb.length() == 0) throw new ParseException("empty atom at offset " + pos[0]);
        return atom(sb.toString());
    }

    public static final class ParseException extends RuntimeException {
        public ParseException(String msg) { super(msg); }
    }
}
