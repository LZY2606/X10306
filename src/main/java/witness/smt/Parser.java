package witness.smt;

import java.util.ArrayList;
import java.util.List;

/** SMT-LIB v2 s-expression tokenizer/parser. Raw text of each command is preserved by callers. */
public final class Parser {

    public static final class ParseError extends RuntimeException {
        public final int offset;
        public ParseError(String msg, int offset) { super(msg + " (at offset " + offset + ")"); this.offset = offset; }
    }

    private final String src;
    private int pos;

    private Parser(String src) { this.src = src; }

    /** Parse all top-level forms. Throws ParseError on malformed input. */
    public static List<SExp> parseAll(String src) {
        Parser p = new Parser(src);
        List<SExp> out = new ArrayList<>();
        p.skipWs();
        while (p.pos < p.src.length()) {
            out.add(p.parseExpr());
            p.skipWs();
        }
        return out;
    }

    /** Parse exactly one form (used for model/core fragments). */
    public static SExp parseOne(String src) {
        Parser p = new Parser(src);
        p.skipWs();
        SExp e = p.parseExpr();
        p.skipWs();
        if (p.pos != p.src.length()) throw new ParseError("trailing content", p.pos);
        return e;
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) { pos++; continue; }
            if (c == ';') { while (pos < src.length() && src.charAt(pos) != '\n') pos++; continue; }
            break;
        }
    }

    private SExp parseExpr() {
        skipWs();
        if (pos >= src.length()) throw new ParseError("unexpected end of input", pos);
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            List<SExp> items = new ArrayList<>();
            while (true) {
                skipWs();
                if (pos >= src.length()) throw new ParseError("unclosed '('", pos);
                if (src.charAt(pos) == ')') { pos++; return SExp.list(items); }
                items.add(parseExpr());
            }
        }
        if (c == ')') throw new ParseError("unexpected ')'", pos);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            pos++;
            while (true) {
                if (pos >= src.length()) throw new ParseError("unclosed string", pos);
                char ch = src.charAt(pos);
                if (ch == '"') {
                    if (pos + 1 < src.length() && src.charAt(pos + 1) == '"') { sb.append('"'); pos += 2; continue; }
                    pos++;
                    return SExp.atom("\"" + sb + "\"");
                }
                sb.append(ch);
                pos++;
            }
        }
        if (c == '|') {
            StringBuilder sb = new StringBuilder();
            pos++;
            while (true) {
                if (pos >= src.length()) throw new ParseError("unclosed quoted symbol", pos);
                char ch = src.charAt(pos);
                if (ch == '|') { pos++; return SExp.atom("|" + sb + "|"); }
                sb.append(ch);
                pos++;
            }
        }
        int start = pos;
        while (pos < src.length()) {
            char ch = src.charAt(pos);
            if (Character.isWhitespace(ch) || ch == '(' || ch == ')' || ch == ';') break;
            pos++;
        }
        if (start == pos) throw new ParseError("empty token", pos);
        return SExp.atom(src.substring(start, pos));
    }
}
