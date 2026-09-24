package smtw.smt;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the SMT-LIB 2 concrete syntax subset used by commands and solver responses.
 * Handles (...), quoted tokens, |...| symbols, ; line comments and "string literals".
 */
public final class SexprParser {

    private final String src;
    private int pos;

    private SexprParser(String src) {
        this.src = src;
    }

    public static List<Sexpr> parseAll(String text) {
        SexprParser p = new SexprParser(text);
        List<Sexpr> out = new ArrayList<>();
        while (true) {
            p.skipWsAndComments();
            if (p.pos >= p.src.length()) break;
            out.add(p.parseOne());
        }
        return out;
    }

    public static Sexpr parseOne(String text) {
        List<Sexpr> all = parseAll(text);
        if (all.size() != 1) {
            throw new SparseException("expected exactly one s-expression, got " + all.size());
        }
        return all.get(0);
    }

    private void skipWsAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == ';') {
                while (pos < src.length() && src.charAt(pos) != '\n') pos++;
            } else {
                break;
            }
        }
    }

    private Sexpr parseOne() {
        skipWsAndComments();
        if (pos >= src.length()) throw new SparseException("unexpected end of input");
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            List<Sexpr> items = new ArrayList<>();
            while (true) {
                skipWsAndComments();
                if (pos >= src.length()) throw new SparseException("unterminated list");
                if (src.charAt(pos) == ')') { pos++; return new Sexpr.List(items); }
                items.add(parseOne());
            }
        }
        if (c == ')') throw new SparseException("unexpected ')'");
        return new Sexpr.Atom(parseAtom());
    }

    private String parseAtom() {
        char c = src.charAt(pos);
        if (c == '|') {
            int start = pos;
            pos++;
            StringBuilder sb = new StringBuilder("|");
            while (pos < src.length()) {
                char ch = src.charAt(pos++);
                sb.append(ch);
                if (ch == '|') return sb.toString();
            }
            throw new SparseException("unterminated quoted symbol at " + start);
        }
        if (c == '"') {
            int start = pos;
            pos++;
            while (pos < src.length()) {
                char ch = src.charAt(pos++);
                if (ch == '"') {
                    if (pos < src.length() && src.charAt(pos) == '"') { pos++; continue; }
                    return src.substring(start, pos);
                }
            }
            throw new SparseException("unterminated string literal at " + start);
        }
        int start = pos;
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (Character.isWhitespace(ch) || ch == '(' || ch == ')') break;
            pos++;
        }
        return src.substring(start, pos);
    }

    public static final class SparseException extends RuntimeException {
        public SparseException(String m) { super(m); }
    }
}
