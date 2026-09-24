package witnessroom.smt;

import java.util.ArrayList;
import java.util.List;

/** S-expression with parser and printer. Raw text of commands is always kept verbatim elsewhere. */
public sealed interface SExpr {
    record Atom(String v) implements SExpr {
        @Override public String toString() { return v; }
    }
    record L(List<SExpr> items) implements SExpr {
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(items.get(i));
            }
            return sb.append(')').toString();
        }
    }

    static SExpr parse(String text) {
        List<SExpr> all = parseAll(text);
        if (all.size() != 1) throw new IllegalArgumentException("expected exactly one s-expression, got " + all.size());
        return all.get(0);
    }

    static List<SExpr> parseAll(String text) {
        List<SExpr> out = new ArrayList<>();
        int[] i = {0};
        while (true) {
            skipWs(text, i);
            if (i[0] >= text.length()) break;
            out.add(read(text, i));
        }
        return out;
    }

    static void skipWs(String s, int[] i) {
        while (i[0] < s.length()) {
            char c = s.charAt(i[0]);
            if (Character.isWhitespace(c)) { i[0]++; continue; }
            if (c == ';') { while (i[0] < s.length() && s.charAt(i[0]) != '\n') i[0]++; continue; }
            break;
        }
    }

    static SExpr read(String s, int[] i) {
        skipWs(s, i);
        char c = s.charAt(i[0]);
        if (c == '(') {
            i[0]++;
            List<SExpr> items = new ArrayList<>();
            while (true) {
                skipWs(s, i);
                if (i[0] >= s.length()) throw new IllegalArgumentException("unclosed (");
                if (s.charAt(i[0]) == ')') { i[0]++; return new L(items); }
                items.add(read(s, i));
            }
        }
        if (c == ')') throw new IllegalArgumentException("unexpected )");
        if (c == '|') {
            int end = s.indexOf('|', i[0] + 1);
            if (end < 0) throw new IllegalArgumentException("unclosed |quoted|");
            String v = s.substring(i[0], end + 1);
            i[0] = end + 1;
            return new Atom(v);
        }
        if (c == '"') {
            StringBuilder sb = new StringBuilder("\"");
            i[0]++;
            while (i[0] < s.length()) {
                char ch = s.charAt(i[0]++);
                sb.append(ch);
                if (ch == '"') break;
            }
            return new Atom(sb.toString());
        }
        int start = i[0];
        while (i[0] < s.length()) {
            char ch = s.charAt(i[0]);
            if (Character.isWhitespace(ch) || ch == '(' || ch == ')' || ch == ';') break;
            i[0]++;
        }
        return new Atom(s.substring(start, i[0]));
    }

    static boolean isAtom(SExpr e, String v) {
        return e instanceof Atom a && a.v().equals(v);
    }

    static String atom(SExpr e) {
        if (e instanceof Atom a) return a.v();
        throw new IllegalArgumentException("expected atom, got " + e);
    }
}
