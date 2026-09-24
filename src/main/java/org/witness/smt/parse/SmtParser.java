package org.witness.smt.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenises and parses an SMT-LIB v2 command stream into a sequence of top-level
 * s-expressions. Comments are stripped but the exact original command text is
 * preserved by callers (the importer keeps per-item raw strings).
 */
public final class SmtParser {

    public static final class ParseFailure extends RuntimeException {
        public final int offset;
        public ParseFailure(String m, int offset) { super(m + " (at byte " + offset + ")"); this.offset = offset; }
    }

    private final String src;
    private int pos;

    private SmtParser(String src) { this.src = src; }

    public static List<SExpr> parseAll(String src) {
        SmtParser p = new SmtParser(src);
        List<SExpr> out = new ArrayList<>();
        while (true) {
            p.skipWsAndComments();
            if (p.eof()) break;
            out.add(p.sexpr());
        }
        return out;
    }

    public static SExpr parseOne(String src) {
        List<SExpr> all = parseAll(src);
        if (all.size() != 1) throw new ParseFailure("expected exactly one expression, found " + all.size(), 0);
        return all.get(0);
    }

    private boolean eof() { return pos >= src.length(); }

    private void skipWsAndComments() {
        while (!eof()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) { pos++; continue; }
            if (c == ';') {
                while (!eof() && src.charAt(pos) != '\n') pos++;
                continue;
            }
            break;
        }
    }

    private SExpr sexpr() {
        skipWsAndComments();
        char c = src.charAt(pos);
        if (c == '(') return list();
        if (c == ')') throw new ParseFailure("unexpected ')'", pos);
        if (c == '|') return quotedSymbol();
        if (c == '"') return stringAtom();
        return atom();
    }

    private SExpr.List list() {
        int start = pos;
        pos++; // (
        List<SExpr> items = new ArrayList<>();
        while (true) {
            skipWsAndComments();
            if (eof()) throw new ParseFailure("unterminated list", start);
            if (src.charAt(pos) == ')') { pos++; return new SExpr.List(items); }
            items.add(sexpr());
        }
    }

    private SExpr.Atom atom() {
        int start = pos;
        while (!eof()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c) || c == '(' || c == ')') break;
            pos++;
        }
        return new SExpr.Atom(src.substring(start, pos));
    }

    private SExpr.Atom quotedSymbol() {
        int start = pos;
        pos++;
        while (!eof()) {
            char c = src.charAt(pos++);
            if (c == '|') return new SExpr.Atom(src.substring(start, pos));
            if (c == '\\') pos++;
        }
        throw new ParseFailure("unterminated quoted symbol", start);
    }

    private SExpr.Atom stringAtom() {
        int start = pos;
        pos++;
        while (!eof()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                if (!eof() && src.charAt(pos) == '"') { pos++; continue; }
                return new SExpr.Atom(src.substring(start, pos));
            }
        }
        throw new ParseFailure("unterminated string literal", start);
    }
}
