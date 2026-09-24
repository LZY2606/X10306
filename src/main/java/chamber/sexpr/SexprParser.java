package chamber.sexpr;

import java.util.ArrayList;
import java.util.List;

/** Tokenizer + parser for SMT-LIB s-expressions. Reports precise format errors. */
public final class SexprParser {

    public static final class ParseException extends RuntimeException {
        public final int offset;
        public ParseException(String message, int offset) {
            super(message + " (at offset " + offset + ")");
            this.offset = offset;
        }
    }

    private final String src;
    private int pos;

    private SexprParser(String src) {
        this.src = src;
        this.pos = 0;
    }

    public static List<Sexpr> parseAll(String src) {
        SexprParser p = new SexprParser(src);
        List<Sexpr> out = new ArrayList<>();
        p.skipWs();
        while (!p.eof()) {
            out.add(p.parseOne());
            p.skipWs();
        }
        return out;
    }

    public static Sexpr parseOne(String src) {
        SexprParser p = new SexprParser(src);
        p.skipWs();
        Sexpr e = p.parseOne();
        p.skipWs();
        if (!p.eof()) throw new ParseException("trailing input after expression", p.pos);
        return e;
    }

    private boolean eof() {
        return pos >= src.length();
    }

    private void skipWs() {
        while (!eof()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == ';') {
                while (!eof() && src.charAt(pos) != '\n') pos++;
            } else {
                return;
            }
        }
    }

    private Sexpr parseOne() {
        if (eof()) throw new ParseException("unexpected end of input", pos);
        char c = src.charAt(pos);
        if (c == '(') {
            int start = pos;
            pos++;
            List<Sexpr> items = new ArrayList<>();
            skipWs();
            while (true) {
                if (eof()) throw new ParseException("unclosed '(' opened", start);
                if (src.charAt(pos) == ')') {
                    pos++;
                    return new Sexpr.SList(items);
                }
                items.add(parseOne());
                skipWs();
            }
        }
        if (c == ')') throw new ParseException("unexpected ')'", pos);
        if (c == '|') {
            int start = pos;
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (eof()) throw new ParseException("unclosed quoted symbol", start);
                char d = src.charAt(pos);
                if (d == '|') {
                    pos++;
                    return new Sexpr.Atom(sb.toString());
                }
                sb.append(d);
                pos++;
            }
        }
        if (c == '"') {
            int start = pos;
            pos++;
            StringBuilder sb = new StringBuilder("\"");
            while (true) {
                if (eof()) throw new ParseException("unclosed string literal", start);
                char d = src.charAt(pos);
                sb.append(d);
                pos++;
                if (d == '"') return new Sexpr.Atom(sb.toString());
            }
        }
        int start = pos;
        while (!eof()) {
            char d = src.charAt(pos);
            if (Character.isWhitespace(d) || d == '(' || d == ')' || d == ';') break;
            pos++;
        }
        if (start == pos) throw new ParseException("empty token", pos);
        return new Sexpr.Atom(src.substring(start, pos));
    }
}
