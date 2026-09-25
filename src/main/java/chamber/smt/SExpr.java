package chamber.smt;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** S-expression with source position, so format errors are precise and replayable. */
public sealed interface SExpr {

    int line();
    int col();

    record Atom(String value, int line, int col) implements SExpr {
        @Override public String toString() { return value; }
    }

    record ListExpr(List<SExpr> items, int line, int col) implements SExpr {
        @Override public String toString() {
            StringJoiner j = new StringJoiner(" ", "(", ")");
            for (SExpr e : items) j.add(e.toString());
            return j.toString();
        }
    }

    static String atom(SExpr e) {
        return e instanceof Atom a ? a.value() : null;
    }

    static List<SExpr> list(SExpr e) {
        return e instanceof ListExpr l ? l.items() : null;
    }

    final class ParseException extends RuntimeException {
        public ParseException(String message, int line, int col) {
            super(message + " at line " + line + ", col " + col);
        }
    }

    /** Parse a stream of s-expressions; throws ParseException with position on malformed input. */
    static List<SExpr> parseAll(String src) {
        List<SExpr> out = new ArrayList<>();
        int[] st = {0, 1, 1}; // index, line, col
        while (true) {
            skipWs(src, st);
            if (st[0] >= src.length()) break;
            out.add(parseOne(src, st));
        }
        return out;
    }

    private static void skipWs(String s, int[] st) {
        while (st[0] < s.length()) {
            char c = s.charAt(st[0]);
            if (c == ';') { // comment to end of line
                while (st[0] < s.length() && s.charAt(st[0]) != '\n') adv(s, st);
            } else if (Character.isWhitespace(c)) {
                adv(s, st);
            } else {
                return;
            }
        }
    }

    private static void adv(String s, int[] st) {
        if (s.charAt(st[0]) == '\n') { st[1]++; st[2] = 1; } else { st[2]++; }
        st[0]++;
    }

    private static SExpr parseOne(String s, int[] st) {
        skipWs(s, st);
        int line = st[1], col = st[2];
        char c = s.charAt(st[0]);
        if (c == '(') {
            adv(s, st);
            List<SExpr> items = new ArrayList<>();
            while (true) {
                skipWs(s, st);
                if (st[0] >= s.length()) throw new ParseException("unclosed '('", line, col);
                if (s.charAt(st[0]) == ')') { adv(s, st); return new ListExpr(items, line, col); }
                items.add(parseOne(s, st));
            }
        }
        if (c == ')') throw new ParseException("unexpected ')'", line, col);
        if (c == '|') { // quoted symbol
            StringBuilder b = new StringBuilder();
            adv(s, st);
            while (st[0] < s.length() && s.charAt(st[0]) != '|') { b.append(s.charAt(st[0])); adv(s, st); }
            if (st[0] >= s.length()) throw new ParseException("unclosed '|'", line, col);
            adv(s, st);
            return new Atom("|" + b + "|", line, col);
        }
        StringBuilder b = new StringBuilder();
        while (st[0] < s.length()) {
            char ch = s.charAt(st[0]);
            if (Character.isWhitespace(ch) || ch == '(' || ch == ')' || ch == ';') break;
            b.append(ch);
            adv(s, st);
        }
        if (b.isEmpty()) throw new ParseException("empty token", line, col);
        return new Atom(b.toString(), line, col);
    }
}
