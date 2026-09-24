package com.witness.smt.sexpr;

import java.util.ArrayList;
import java.util.List;

/**
 * Lenient SMT-LIB tokenizer/parser.
 * Handles ; comments, |quoted symbols|, "string literals", (...) nesting.
 * Malformed input throws ParseError carrying a 1-based line number.
 */
public final class SExprParser {

    public static final class ParseError extends RuntimeException {
        private final int line;
        public ParseError(String msg, int line) { super(msg + " (line " + line + ")"); this.line = line; }
        public int line() { return line; }
    }

    private final String src;
    private int pos;
    private int line = 1;

    private SExprParser(String src) { this.src = src; }

    public static List<SExpr> parseAll(String src) {
        SExprParser p = new SExprParser(src);
        List<SExpr> out = new ArrayList<>();
        p.skipWs();
        while (p.pos < p.src.length()) {
            out.add(p.readExpr());
            p.skipWs();
        }
        return out;
    }

    public static SExpr parseOne(String src) {
        List<SExpr> all = parseAll(src);
        if (all.size() != 1) {
            throw new ParseError("expected exactly one expression, found " + all.size(), 1);
        }
        return all.get(0);
    }

    private SExpr readExpr() {
        char c = src.charAt(pos);
        if (c == '(') {
            int openLine = line;
            pos++;
            List<SExpr> kids = new ArrayList<>();
            skipWs();
            while (pos < src.length() && src.charAt(pos) != ')') {
                kids.add(readExpr());
                skipWs();
            }
            if (pos >= src.length()) {
                throw new ParseError("unclosed parenthesis", openLine);
            }
            pos++; // consume ')'
            return SExpr.list(kids);
        }
        if (c == ')') throw new ParseError("unexpected ')'", line);
        return readAtom();
    }

    private SExpr readAtom() {
        int start = pos;
        char c = src.charAt(pos);
        if (c == '|') {
            pos++;
            while (pos < src.length() && src.charAt(pos) != '|') {
                if (src.charAt(pos) == '\n') line++;
                pos++;
            }
            if (pos >= src.length()) throw new ParseError("unterminated quoted symbol", line);
            pos++; // closing |
            return new SExpr.Atom(src.substring(start, pos));
        }
        if (c == '"') {
            pos++;
            while (pos < src.length()) {
                char q = src.charAt(pos);
                if (q == '\n') line++;
                if (q == '"') {
                    if (pos + 1 < src.length() && src.charAt(pos + 1) == '"') { pos += 2; continue; }
                    pos++;
                    return new SExpr.Atom(src.substring(start, pos));
                }
                pos++;
            }
            throw new ParseError("unterminated string literal", line);
        }
        while (pos < src.length()) {
            char q = src.charAt(pos);
            if (Character.isWhitespace(q) || q == '(' || q == ')') break;
            pos++;
        }
        return new SExpr.Atom(src.substring(start, pos));
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                if (c == '\n') line++;
                pos++;
            } else if (c == ';') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
            } else {
                break;
            }
        }
    }
}
