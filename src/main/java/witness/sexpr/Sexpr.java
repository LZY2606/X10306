package witness.sexpr;

import java.util.ArrayList;
import java.util.List;

/** S-expression with raw atom text preserved (原始表示保留). */
public abstract class Sexpr {
    private Sexpr() {}

    public static final class Atom extends Sexpr {
        public final String text;
        public Atom(String text) { this.text = text; }
        @Override public String toString() { return text; }
    }

    public static final class Lst extends Sexpr {
        public final List<Sexpr> items;
        public Lst(List<Sexpr> items) { this.items = items; }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(items.get(i));
            }
            return sb.append(')').toString();
        }
    }

    public boolean isAtom() { return this instanceof Atom; }
    public String atom() { return ((Atom) this).text; }
    public List<Sexpr> items() { return ((Lst) this).items; }
    public Sexpr get(int i) { return ((Lst) this).items.get(i); }
    public int size() { return ((Lst) this).items.size(); }
    public String head() {
        if (this instanceof Lst l && !l.items.isEmpty() && l.items.get(0) instanceof Atom a) return a.text;
        return "";
    }

    public static List<Sexpr> parseAll(String src) {
        Parser p = new Parser(src);
        List<Sexpr> out = new ArrayList<>();
        while (true) {
            p.skipWs();
            if (p.eof()) break;
            out.add(p.expr());
        }
        return out;
    }

    public static Sexpr parseOne(String src) {
        List<Sexpr> all = parseAll(src);
        if (all.size() != 1) throw new IllegalArgumentException("expected exactly 1 expression, got " + all.size());
        return all.get(0);
    }

    static final class Parser {
        private final String s; private int i;
        Parser(String s) { this.s = s; }
        boolean eof() { return i >= s.length(); }
        void skipWs() {
            while (!eof()) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) { i++; continue; }
                if (c == ';') { while (!eof() && s.charAt(i) != '\n') i++; continue; }
                break;
            }
        }
        Sexpr expr() {
            skipWs();
            if (eof()) throw new IllegalArgumentException("unexpected end of input");
            char c = s.charAt(i);
            if (c == '(') {
                i++;
                List<Sexpr> items = new ArrayList<>();
                while (true) {
                    skipWs();
                    if (eof()) throw new IllegalArgumentException("unclosed '('");
                    if (s.charAt(i) == ')') { i++; return new Lst(items); }
                    items.add(expr());
                }
            }
            if (c == ')') throw new IllegalArgumentException("unexpected ')'");
            if (c == '|') { // quoted symbol
                StringBuilder sb = new StringBuilder();
                i++;
                while (!eof() && s.charAt(i) != '|') { sb.append(s.charAt(i)); i++; }
                if (eof()) throw new IllegalArgumentException("unclosed '|'");
                i++;
                return new Atom("|" + sb + "|");
            }
            if (c == '"') { // string literal, kept raw
                StringBuilder sb = new StringBuilder("\"");
                i++;
                while (!eof()) {
                    char d = s.charAt(i);
                    sb.append(d); i++;
                    if (d == '"') {
                        if (!eof() && s.charAt(i) == '"') { sb.append('"'); i++; continue; }
                        break;
                    }
                }
                return new Atom(sb.toString());
            }
            StringBuilder sb = new StringBuilder();
            while (!eof()) {
                char d = s.charAt(i);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == ';') break;
                sb.append(d); i++;
            }
            if (sb.length() == 0) throw new IllegalArgumentException("empty atom at " + i);
            return new Atom(sb.toString());
        }
    }
}
