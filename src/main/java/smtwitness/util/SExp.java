package smtwitness.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable S-expression: either an atom (token) or a list of children.
 * Quoted SMT-LIB string literals are preserved with surrounding quotes.
 */
public final class SExp {
    public final String atom;
    public final List<SExp> list;

    private SExp(String atom, List<SExp> list) {
        this.atom = atom;
        this.list = list;
    }

    public static SExp atom(String s) {
        return new SExp(s, null);
    }

    public static SExp list(List<SExp> xs) {
        return new SExp(null, List.copyOf(xs));
    }

    public boolean isAtom() {
        return list == null;
    }

    public boolean isList() {
        return list != null;
    }

    public int size() {
        return list == null ? 0 : list.size();
    }

    public SExp get(int i) {
        return list.get(i);
    }

    /** Head symbol of a list expression, e.g. "assert" in "(assert ...)". */
    public String head() {
        if (list == null || list.isEmpty()) return null;
        SExp h = list.get(0);
        return h.isAtom() ? h.atom : null;
    }

    @Override
    public String toString() {
        if (isAtom()) return atom;
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(list.get(i).toString());
        }
        return sb.append(')').toString();
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    public static final class ParseException extends Exception {
        public final int line;
        public ParseException(String msg, int line) {
            super(msg + " (line " + line + ")");
            this.line = line;
        }
    }

    /** Parse exactly one expression; trailing content is an error. */
    public static SExp parseOne(String input) throws ParseException {
        List<Token> toks = tokenize(input);
        Parser p = new Parser(toks);
        SExp e = p.parseExpr();
        if (p.pos < toks.size()) {
            throw new ParseException("trailing content after top-level expression",
                    toks.get(p.pos).line);
        }
        return e;
    }

    /** Parse all top-level expressions in order (multiline tolerant). */
    public static List<SExp> parseAll(String input) throws ParseException {
        List<Token> toks = tokenize(input);
        Parser p = new Parser(toks);
        List<SExp> out = new ArrayList<>();
        while (p.pos < toks.size()) {
            out.add(p.parseExpr());
        }
        return out;
    }

    private record Token(String text, int line) {}

    private static List<Token> tokenize(String input) throws ParseException {
        List<Token> toks = new ArrayList<>();
        int n = input.length();
        int line = 1;
        int i = 0;
        while (i < n) {
            char c = input.charAt(i);
            if (c == '\n') { line++; i++; continue; }
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == ';') {
                while (i < n && input.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '(' || c == ')') {
                toks.add(new Token(String.valueOf(c), line));
                i++;
                continue;
            }
            if (c == '|') {
                int start = line;
                StringBuilder sb = new StringBuilder("|");
                i++;
                while (i < n && input.charAt(i) != '|') {
                    if (input.charAt(i) == '\n') line++;
                    sb.append(input.charAt(i));
                    i++;
                }
                if (i >= n) throw new ParseException("unterminated quoted symbol", start);
                sb.append('|');
                i++;
                toks.add(new Token(sb.toString(), start));
                continue;
            }
            if (c == '"') {
                int start = line;
                StringBuilder sb = new StringBuilder("\"");
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = input.charAt(i);
                    if (d == '\n') line++;
                    if (d == '"') {
                        if (i + 1 < n && input.charAt(i + 1) == '"') {
                            sb.append("\"\"");
                            i += 2;
                            continue;
                        }
                        sb.append('"');
                        i++;
                        closed = true;
                        break;
                    }
                    sb.append(d);
                    i++;
                }
                if (!closed) throw new ParseException("unterminated string literal", start);
                toks.add(new Token(sb.toString(), start));
                continue;
            }
            int start = i;
            while (i < n) {
                char d = input.charAt(i);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == ';') break;
                i++;
            }
            toks.add(new Token(input.substring(start, i), line));
        }
        return toks;
    }

    private static final class Parser {
        final List<Token> toks;
        int pos;
        Parser(List<Token> toks) { this.toks = toks; }

        SExp parseExpr() throws ParseException {
            if (pos >= toks.size()) {
                throw new ParseException("unexpected end of input", -1);
            }
            Token t = toks.get(pos++);
            if (")".equals(t.text)) {
                throw new ParseException("unexpected ')'", t.line);
            }
            if ("(".equals(t.text)) {
                List<SExp> children = new ArrayList<>();
                while (true) {
                    if (pos >= toks.size()) {
                        throw new ParseException("missing closing ')'", t.line);
                    }
                    if (")".equals(toks.get(pos).text)) {
                        pos++;
                        return SExp.list(children);
                    }
                    children.add(parseExpr());
                }
            }
            return SExp.atom(t.text);
        }
    }
}
