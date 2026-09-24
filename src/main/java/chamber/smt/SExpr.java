package chamber.smt;

import java.util.ArrayList;
import java.util.List;

/** S-expression: either an atom or a list of S-expressions. */
public final class SExpr {
    public final String atom;
    public final List<SExpr> items;

    private SExpr(String atom, List<SExpr> items) {
        this.atom = atom;
        this.items = items;
    }

    public static SExpr atom(String a) { return new SExpr(a, null); }
    public static SExpr list(List<SExpr> l) { return new SExpr(null, l); }

    public boolean isAtom() { return atom != null; }
    public boolean isList() { return items != null; }

    public SExpr get(int i) { return items.get(i); }
    public int size() { return items.size(); }
    public String head() { return items.isEmpty() ? "" : items.get(0).atomOr(""); }

    public String atomOr(String dflt) { return atom == null ? dflt : atom; }

    @Override
    public String toString() {
        if (atom != null) return atom;
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(items.get(i).toString());
        }
        return sb.append(')').toString();
    }

    /** Parser for a small SMT-LIB s-expression subset. Throws ParseException on malformed input. */
    public static final class Parser {
        private final String src;
        private int pos;

        public Parser(String src) { this.src = src; }

        public static List<SExpr> parseAll(String src) throws ParseException {
            Parser p = new Parser(src);
            List<SExpr> out = new ArrayList<>();
            while (true) {
                p.skipWs();
                if (p.pos >= p.src.length()) break;
                out.add(p.parseOne());
            }
            return out;
        }

        private void skipWs() throws ParseException {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) { pos++; continue; }
                if (c == ';') { while (pos < src.length() && src.charAt(pos) != '\n') pos++; continue; }
                break;
            }
        }

        private SExpr parseOne() throws ParseException {
            skipWs();
            if (pos >= src.length()) throw new ParseException("unexpected end of input at offset " + pos);
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                List<SExpr> items = new ArrayList<>();
                while (true) {
                    skipWs();
                    if (pos >= src.length()) throw new ParseException("unclosed '(' at offset " + pos);
                    if (src.charAt(pos) == ')') { pos++; return SExpr.list(items); }
                    items.add(parseOne());
                }
            }
            if (c == ')') throw new ParseException("unexpected ')' at offset " + pos);
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                pos++;
                while (pos < src.length()) {
                    char d = src.charAt(pos);
                    if (d == '"') {
                        if (pos + 1 < src.length() && src.charAt(pos + 1) == '"') { sb.append('"'); pos += 2; continue; }
                        pos++;
                        return SExpr.atom("\"" + sb + "\"");
                    }
                    sb.append(d);
                    pos++;
                }
                throw new ParseException("unterminated string at offset " + pos);
            }
            if (c == '|') {
                StringBuilder sb = new StringBuilder();
                pos++;
                while (pos < src.length() && src.charAt(pos) != '|') { sb.append(src.charAt(pos)); pos++; }
                if (pos >= src.length()) throw new ParseException("unterminated quoted symbol at offset " + pos);
                pos++;
                return SExpr.atom("|" + sb + "|");
            }
            int start = pos;
            while (pos < src.length()) {
                char d = src.charAt(pos);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == ';') break;
                pos++;
            }
            if (start == pos) throw new ParseException("empty token at offset " + pos);
            return SExpr.atom(src.substring(start, pos));
        }
    }

    public static final class ParseException extends Exception {
        public ParseException(String msg) { super(msg); }
    }
}
