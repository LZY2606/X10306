package witness.sexpr;

import java.util.ArrayList;
import java.util.List;

/**
 * S-Expression tokenizer/parser. Supports:
 *  - quoted string literals (with SMT-LIB style escapes, |quoted| symbols left intact as atoms)
 *  - line comments ";" (captured verbatim, attached to the following node)
 *  - keyword tokens such as :named.
 */
public final class SExprParser {
    private final String src;
    private int pos;
    private int line = 1;
    private int col = 1;
    private final List<String> errors = new ArrayList<>();

    public SExprParser(String src) { this.src = src; }

    public static List<SExpr> parseAll(String src) {
        SExprParser p = new SExprParser(src);
        List<SExpr> out = new ArrayList<>();
        while (true) {
            String comments = p.skipWsAndComments();
            if (p.eof()) break;
            out.add(p.node(comments));
        }
        return out;
    }

    public static List<String> parseErrors(String src) {
        SExprParser p = new SExprParser(src);
        try {
            while (true) {
                p.skipWsAndComments();
                if (p.eof()) break;
                p.node("");
            }
        } catch (RuntimeException e) {
            p.errors.add(e.getMessage());
        }
        return p.errors;
    }

    private boolean eof() { return pos >= src.length(); }
    private char peek() { return src.charAt(pos); }

    private void advance() {
        if (src.charAt(pos) == '\n') { line++; col = 1; } else col++;
        pos++;
    }

    /** Skips whitespace/comments and returns the concatenated comment block. */
    private String skipWsAndComments() {
        StringBuilder comments = new StringBuilder();
        while (!eof()) {
            char c = peek();
            if (Character.isWhitespace(c)) advance();
            else if (c == ';') {
                int start = pos;
                while (!eof() && peek() != '\n') advance();
                if (comments.length() > 0) comments.append('\n');
                comments.append(src, start, pos);
            } else break;
        }
        return comments.toString();
    }

    private SExpr node(String comments) {
        int sl = line, sc = col;
        char c = peek();
        if (c == '(') {
            advance();
            List<SExpr> kids = new ArrayList<>();
            while (true) {
                String inner = skipWsAndComments();
                if (eof()) throw new IllegalArgumentException("unclosed '(' at line " + sl + ":" + sc);
                if (peek() == ')') { advance(); break; }
                kids.add(node(inner));
            }
            return SExpr.list(kids, sl, sc, comments);
        }
        if (c == ')') throw new IllegalArgumentException("unexpected ')' at line " + line + ":" + col);
        return SExpr.atom(atomToken(), sl, sc, comments);
    }

    private String atomToken() {
        int start = pos;
        if (peek() == '"') {
            advance();
            while (!eof()) {
                char c = peek();
                if (c == '"') {
                    advance();
                    if (!eof() && peek() == '"') { advance(); continue; }
                    break;
                }
                advance();
            }
            return src.substring(start, pos);
        }
        if (peek() == '|') {
            advance();
            while (!eof() && peek() != '|') advance();
            if (!eof()) advance();
            return src.substring(start, pos);
        }
        while (!eof()) {
            char c = peek();
            if (Character.isWhitespace(c) || c == '(' || c == ')') break;
            advance();
        }
        return src.substring(start, pos);
    }
}
