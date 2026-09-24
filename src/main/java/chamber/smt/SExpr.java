package chamber.smt;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** S-expression: the single term/command representation for the supported SMT-LIB subset. */
public sealed interface SExpr {

    record Atom(String value) implements SExpr {
        @Override public String toString() { return value; }
    }

    record ListExpr(List<SExpr> items) implements SExpr {
        @Override public String toString() {
            StringJoiner j = new StringJoiner(" ", "(", ")");
            for (SExpr e : items) j.add(e.toString());
            return j.toString();
        }
    }

    static Atom atom(String v) { return new Atom(v); }

    static ListExpr list(SExpr... items) { return new ListExpr(List.of(items)); }

    static ListExpr list(List<SExpr> items) { return new ListExpr(List.copyOf(items)); }

    default boolean isAtom() { return this instanceof Atom; }

    default String asAtom() {
        if (this instanceof Atom a) return a.value();
        throw new IllegalStateException("expected atom, got " + this);
    }

    default List<SExpr> asList() {
        if (this instanceof ListExpr l) return l.items();
        throw new IllegalStateException("expected list, got " + this);
    }

    /** Tokenize + parse all toplevel s-expressions in {@code text}.
     *  Comments (';' to end of line) are skipped. Throws ParseException on malformed input. */
    static List<SExpr> parseAll(String text) throws ParseException {
        List<String> tokens = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inComment = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inComment) {
                if (c == '\n') inComment = false;
                continue;
            }
            if (c == ';') { inComment = true; flush(tokens, cur); continue; }
            if (c == '(' || c == ')') { flush(tokens, cur); tokens.add(String.valueOf(c)); continue; }
            if (c == '|') { // quoted symbol |...|
                flush(tokens, cur);
                int end = text.indexOf('|', i + 1);
                if (end < 0) throw new ParseException("unterminated quoted symbol at offset " + i);
                tokens.add(text.substring(i, end + 1));
                i = end;
                continue;
            }
            if (Character.isWhitespace(c)) { flush(tokens, cur); continue; }
            cur.append(c);
        }
        flush(tokens, cur);

        List<SExpr> out = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < tokens.size()) {
            out.add(parseOne(tokens, pos));
        }
        return out;
    }

    private static void flush(List<String> tokens, StringBuilder cur) {
        if (cur.length() > 0) { tokens.add(cur.toString()); cur.setLength(0); }
    }

    private static SExpr parseOne(List<String> tokens, int[] pos) throws ParseException {
        String t = tokens.get(pos[0]++);
        if (t.equals("(")) {
            List<SExpr> items = new ArrayList<>();
            while (true) {
                if (pos[0] >= tokens.size()) throw new ParseException("unbalanced '(' — missing ')'");
                if (tokens.get(pos[0]).equals(")")) { pos[0]++; return new ListExpr(items); }
                items.add(parseOne(tokens, pos));
            }
        }
        if (t.equals(")")) throw new ParseException("unexpected ')'");
        return new Atom(t);
    }

    final class ParseException extends Exception {
        public ParseException(String msg) { super(msg); }
    }
}
