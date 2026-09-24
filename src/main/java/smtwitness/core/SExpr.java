package smtwitness.core;

import java.util.ArrayList;
import java.util.List;

/** S-expression AST. Atoms keep their raw token text. */
public sealed interface SExpr {
    record Atom(String text) implements SExpr {}
    record ListExpr(List<SExpr> items) implements SExpr {}

    static SExpr atom(String text) { return new Atom(text); }
    static SExpr list(List<SExpr> items) { return new ListExpr(items); }

    default boolean isAtom() { return this instanceof Atom; }
    default boolean isList() { return this instanceof ListExpr; }
    default String atomText() {
        if (this instanceof Atom a) return a.text();
        throw new IllegalStateException("not an atom: " + this);
    }
    default List<SExpr> items() {
        if (this instanceof ListExpr l) return l.items();
        throw new IllegalStateException("not a list: " + this);
    }
    default String head() {
        if (this instanceof ListExpr l && !l.items().isEmpty() && l.items().get(0) instanceof Atom a) return a.text();
        return "";
    }

    /** Parse one or more top-level s-expressions. Throws ParseException on malformed input. */
    static List<SExpr> parseAll(String src) throws ParseException {
        Parser p = new Parser(src);
        List<SExpr> out = new ArrayList<>();
        p.skipTrivia();
        while (!p.eof()) {
            out.add(p.parseExpr());
            p.skipTrivia();
        }
        return out;
    }

    /** Parse exactly one s-expression. */
    static SExpr parseOne(String src) throws ParseException {
        List<SExpr> all = parseAll(src);
        if (all.size() != 1) throw new ParseException("expected exactly one s-expression, got " + all.size());
        return all.get(0);
    }

    final class ParseException extends Exception {
        public ParseException(String message) { super(message); }
    }

    final class Parser {
        private final String src;
        private int pos;

        Parser(String src) { this.src = src; }

        boolean eof() { return pos >= src.length(); }

        void skipTrivia() {
            while (!eof()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) { pos++; continue; }
                if (c == ';') { while (!eof() && src.charAt(pos) != '\n') pos++; continue; }
                break;
            }
        }

        SExpr parseExpr() throws ParseException {
            skipTrivia();
            if (eof()) throw new ParseException("unexpected end of input");
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                List<SExpr> items = new ArrayList<>();
                while (true) {
                    skipTrivia();
                    if (eof()) throw new ParseException("unbalanced parentheses: missing ')'");
                    if (src.charAt(pos) == ')') { pos++; return new ListExpr(items); }
                    items.add(parseExpr());
                }
            }
            if (c == ')') throw new ParseException("unexpected ')'");
            if (c == '"') return parseString();
            if (c == '|') return parseQuotedSymbol();
            return parseAtom();
        }

        private SExpr parseString() throws ParseException {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (!eof()) {
                char c = src.charAt(pos);
                if (c == '"') {
                    if (pos + 1 < src.length() && src.charAt(pos + 1) == '"') { sb.append('"'); pos += 2; continue; }
                    pos++;
                    return new Atom("\"" + sb + "\"");
                }
                sb.append(c);
                pos++;
            }
            throw new ParseException("unterminated string literal");
        }

        private SExpr parseQuotedSymbol() throws ParseException {
            pos++;
            StringBuilder sb = new StringBuilder("|");
            while (!eof()) {
                char c = src.charAt(pos);
                sb.append(c);
                pos++;
                if (c == '|') return new Atom(sb.toString());
            }
            throw new ParseException("unterminated quoted symbol");
        }

        private SExpr parseAtom() {
            int start = pos;
            while (!eof()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c) || c == '(' || c == ')' || c == ';') break;
                pos++;
            }
            return new Atom(src.substring(start, pos));
        }
    }
}
