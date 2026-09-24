package room.testimony;

import java.util.ArrayList;
import java.util.List;

/** S-expression with raw source spans, so the original command stream is preserved verbatim. */
public sealed interface Sexp {
    record Atom(String text) implements Sexp {}
    record ListNode(List<Sexp> items) implements Sexp {}
    record Span(Sexp sexp, int start, int end) {}

    default boolean isAtom() {
        return this instanceof Atom;
    }

    default String atom() {
        return this instanceof Atom a ? a.text() : null;
    }

    default List<Sexp> items() {
        return this instanceof ListNode l ? l.items() : null;
    }

    default Sexp head() {
        return this instanceof ListNode l && !l.items().isEmpty() ? l.items().get(0) : null;
    }

    default String headAtom() {
        Sexp h = head();
        return h == null ? null : h.atom();
    }

    static String str(Sexp s) {
        if (s instanceof Atom a) return a.text();
        StringBuilder b = new StringBuilder("(");
        boolean first = true;
        for (Sexp x : s.items()) {
            if (!first) b.append(' ');
            b.append(str(x));
            first = false;
        }
        return b.append(')').toString();
    }

    record Tok(String text, int start, int end) {}

    static List<Tok> tokenize(String text) {
        List<Tok> toks = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == ';') {
                while (i < n && text.charAt(i) != '\n') i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '(' || c == ')') {
                toks.add(new Tok(String.valueOf(c), i, i + 1));
                i++;
                continue;
            }
            if (c == '"') {
                int j = i + 1;
                while (j < n && text.charAt(j) != '"') j++;
                if (j >= n) throw new IllegalArgumentException("unterminated string literal at offset " + i);
                toks.add(new Tok(text.substring(i, j + 1), i, j + 1));
                i = j + 1;
                continue;
            }
            if (c == '|') {
                int j = i + 1;
                while (j < n && text.charAt(j) != '|') j++;
                if (j >= n) throw new IllegalArgumentException("unterminated quoted symbol at offset " + i);
                toks.add(new Tok(text.substring(i, j + 1), i, j + 1));
                i = j + 1;
                continue;
            }
            int j = i;
            while (j < n) {
                char d = text.charAt(j);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == ';') break;
                j++;
            }
            if (j == i) throw new IllegalArgumentException("unexpected character '" + c + "' at offset " + i);
            toks.add(new Tok(text.substring(i, j), i, j));
            i = j;
        }
        return toks;
    }

    static List<Span> parseAllWithSpans(String text) {
        List<Tok> toks = tokenize(text);
        List<Span> out = new ArrayList<>();
        int[] pos = {0};
        while (pos[0] < toks.size()) {
            int start = toks.get(pos[0]).start();
            Sexp s = parseOne(toks, pos);
            out.add(new Span(s, start, toks.get(pos[0] - 1).end()));
        }
        return out;
    }

    static List<Sexp> parseAll(String text) {
        List<Sexp> out = new ArrayList<>();
        for (Span s : parseAllWithSpans(text)) out.add(s.sexp());
        return out;
    }

    private static Sexp parseOne(List<Tok> toks, int[] pos) {
        Tok t = toks.get(pos[0]++);
        if (t.text().equals("(")) {
            List<Sexp> items = new ArrayList<>();
            while (true) {
                if (pos[0] >= toks.size()) throw new IllegalArgumentException("unbalanced '(' near offset " + t.start());
                if (toks.get(pos[0]).text().equals(")")) {
                    pos[0]++;
                    return new ListNode(items);
                }
                items.add(parseOne(toks, pos));
            }
        }
        if (t.text().equals(")")) throw new IllegalArgumentException("unexpected ')' at offset " + t.start());
        return new Atom(t.text());
    }
}
