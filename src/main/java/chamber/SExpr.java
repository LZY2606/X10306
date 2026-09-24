package chamber;

import java.util.ArrayList;
import java.util.List;

/** S-expression tree with raw-source spans, for the SMT-LIB command stream. */
public sealed interface SExpr {
    record Atom(String text) implements SExpr {}
    record SList(List<SExpr> items) implements SExpr {}

    /** A top-level form together with its exact raw text (preserved verbatim). */
    record Top(SExpr expr, String raw, int offset) {}

    static boolean isAtom(SExpr e) { return e instanceof Atom; }

    static String atom(SExpr e) {
        if (e instanceof Atom a) return a.text();
        throw new IllegalArgumentException("not an atom: " + e);
    }

    static List<SExpr> items(SExpr e) {
        if (e instanceof SList l) return l.items();
        throw new IllegalArgumentException("not a list: " + e);
    }

    static String head(SExpr e) {
        if (e instanceof SList l && !l.items().isEmpty() && l.items().get(0) instanceof Atom a) return a.text();
        return "";
    }

    static String render(SExpr e) {
        if (e instanceof Atom a) return a.text();
        StringBuilder sb = new StringBuilder("(");
        List<SExpr> items = ((SList) e).items();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(render(items.get(i)));
        }
        return sb.append(')').toString();
    }

    /** Parse all top-level forms, keeping raw slices. Throws on malformed input. */
    static List<Top> parseAll(String src) {
        List<Top> out = new ArrayList<>();
        int[] pos = {0};
        int n = src.length();
        while (true) {
            skipWs(src, pos);
            if (pos[0] >= n) return out;
            int start = pos[0];
            SExpr e = parseOne(src, pos);
            out.add(new Top(e, src.substring(start, pos[0]), start));
        }
    }

    static SExpr parse(String src) {
        List<Top> tops = parseAll(src);
        if (tops.size() != 1) throw new IllegalArgumentException("expected exactly 1 form, got " + tops.size());
        return tops.get(0).expr();
    }

    private static void skipWs(String s, int[] pos) {
        int n = s.length();
        while (pos[0] < n) {
            char c = s.charAt(pos[0]);
            if (Character.isWhitespace(c)) pos[0]++;
            else if (c == ';') {
                while (pos[0] < n && s.charAt(pos[0]) != '\n') pos[0]++;
            } else return;
        }
    }

    private static SExpr parseOne(String s, int[] pos) {
        skipWs(s, pos);
        if (pos[0] >= s.length()) throw new IllegalArgumentException("unexpected end of input");
        char c = s.charAt(pos[0]);
        if (c == '(') {
            pos[0]++;
            List<SExpr> items = new ArrayList<>();
            while (true) {
                skipWs(s, pos);
                if (pos[0] >= s.length()) throw new IllegalArgumentException("unclosed '(' at " + pos[0]);
                if (s.charAt(pos[0]) == ')') {
                    pos[0]++;
                    return new SList(items);
                }
                items.add(parseOne(s, pos));
            }
        }
        if (c == ')') throw new IllegalArgumentException("unexpected ')' at " + pos[0]);
        if (c == '"') {
            StringBuilder sb = new StringBuilder("\"");
            pos[0]++;
            while (true) {
                if (pos[0] >= s.length()) throw new IllegalArgumentException("unclosed string literal");
                char ch = s.charAt(pos[0]++);
                sb.append(ch);
                if (ch == '"') return new Atom(sb.toString());
            }
        }
        if (c == '|') {
            StringBuilder sb = new StringBuilder("|");
            pos[0]++;
            while (true) {
                if (pos[0] >= s.length()) throw new IllegalArgumentException("unclosed quoted symbol");
                char ch = s.charAt(pos[0]++);
                sb.append(ch);
                if (ch == '|') return new Atom(sb.toString());
            }
        }
        int start = pos[0];
        while (pos[0] < s.length()) {
            char ch = s.charAt(pos[0]);
            if (Character.isWhitespace(ch) || ch == '(' || ch == ')' || ch == ';') break;
            pos[0]++;
        }
        if (start == pos[0]) throw new IllegalArgumentException("empty token at " + pos[0]);
        return new Atom(s.substring(start, pos[0]));
    }
}
