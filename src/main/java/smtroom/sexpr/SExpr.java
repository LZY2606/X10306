package smtroom.sexpr;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/** S-expression AST. Atoms keep their exact source text. */
public sealed interface SExpr {
    record Atom(String text) implements SExpr {}
    record ListExpr(List<SExpr> items) implements SExpr {}

    static SExpr atom(String text) { return new Atom(text); }

    static SExpr list(SExpr... items) { return new ListExpr(List.of(items)); }

    static SExpr list(List<SExpr> items) { return new ListExpr(List.copyOf(items)); }

    default boolean isAtom() { return this instanceof Atom; }

    default String atom() {
        if (this instanceof Atom a) return a.text;
        throw new IllegalStateException("not an atom: " + this);
    }

    default List<SExpr> items() {
        if (this instanceof ListExpr l) return l.items;
        throw new IllegalStateException("not a list: " + this);
    }

    default SExpr get(int i) { return items().get(i); }

    default int size() { return items().size(); }

    default String head() {
        if (this instanceof ListExpr l && !l.items.isEmpty() && l.items.get(0) instanceof Atom a)
            return a.text;
        return "";
    }

    static String print(SExpr e) {
        if (e instanceof Atom a) return a.text;
        StringJoiner j = new StringJoiner(" ", "(", ")");
        for (SExpr c : e.items()) j.add(print(c));
        return j.toString();
    }

    /** Tokenizer + parser for a whole text possibly containing many s-exprs. */
    static List<SExpr> parseAll(String src) {
        List<String> toks = tokenize(src);
        List<SExpr> out = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < toks.size()) out.add(parseOne(toks, pos));
        return out;
    }

    static SExpr parse(String src) {
        List<SExpr> all = parseAll(src);
        if (all.size() != 1) throw new SmtParseException("expected exactly 1 s-expr, got " + all.size());
        return all.get(0);
    }

    private static SExpr parseOne(List<String> toks, int[] pos) {
        String t = toks.get(pos[0]++);
        if (t.equals("(")) {
            List<SExpr> items = new ArrayList<>();
            while (true) {
                if (pos[0] >= toks.size()) throw new SmtParseException("unclosed '('");
                if (toks.get(pos[0]).equals(")")) { pos[0]++; return new ListExpr(items); }
                items.add(parseOne(toks, pos));
            }
        }
        if (t.equals(")")) throw new SmtParseException("unexpected ')'");
        return new Atom(t);
    }

    private static List<String> tokenize(String src) {
        List<String> toks = new ArrayList<>();
        int i = 0, n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == ';') { while (i < n && src.charAt(i) != '\n') i++; continue; }
            if (c == '(' || c == ')') { toks.add(String.valueOf(c)); i++; continue; }
            if (c == '|') { // quoted symbol
                int j = src.indexOf('|', i + 1);
                if (j < 0) throw new SmtParseException("unclosed '|'");
                toks.add(src.substring(i, j + 1)); i = j + 1; continue;
            }
            if (c == '"') { // string literal
                StringBuilder sb = new StringBuilder("\"");
                i++;
                while (i < n) {
                    char d = src.charAt(i);
                    sb.append(d); i++;
                    if (d == '"') break;
                }
                toks.add(sb.toString()); continue;
            }
            int j = i;
            while (j < n) {
                char d = src.charAt(j);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == ';') break;
                j++;
            }
            toks.add(src.substring(i, j)); i = j;
        }
        return toks;
    }

    class SmtParseException extends RuntimeException {
        public SmtParseException(String msg) { super(msg); }
    }
}
