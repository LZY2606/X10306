package smtwitness.smt;

import java.util.ArrayList;
import java.util.List;

/** Minimal S-expression model used by the local finite semantic subset. */
public sealed interface SExpr permits SExpr.Atom, SExpr.SList {
    String render();

    static SExpr parse(String text) {
        Parser p = new Parser(text);
        p.skipWs();
        SExpr e = p.read();
        p.skipWs();
        if (!p.eof()) {
            throw new IllegalArgumentException("trailing tokens at offset " + p.pos);
        }
        return e;
    }

    /** Parse all top-level s-expressions (a transcript is a sequence of them). */
    static List<SExpr> parseAll(String text) {
        Parser p = new Parser(text);
        List<SExpr> out = new ArrayList<>();
        p.skipWs();
        while (!p.eof()) {
            out.add(p.read());
            p.skipWs();
        }
        return out;
    }

    record Atom(String text) implements SExpr {
        @Override public String render() { return text; }
    }

    record SList(List<SExpr> items) implements SExpr {
        public SList { items = List.copyOf(items); }
        public SExpr head() { return items.get(0); }
        public String headSym() { return items.get(0) instanceof Atom a ? a.text() : null; }
        @Override public String render() {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(items.get(i).render());
            }
            return sb.append(')').toString();
        }
    }

    final class Parser {
        private final String s;
        int pos;
        Parser(String s) { this.s = s; }
        boolean eof() { return pos >= s.length(); }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (Character.isWhitespace(c)) { pos++; continue; }
                if (c == ';') {
                    while (pos < s.length() && s.charAt(pos) != '\n') pos++;
                    continue;
                }
                break;
            }
        }

        SExpr read() {
            skipWs();
            if (eof()) throw new IllegalArgumentException("unexpected end of input");
            char c = s.charAt(pos);
            if (c == '(') return readList();
            if (c == '|') return readQuotedSym();
            return readAtom();
        }

        private SExpr readList() {
            if (s.charAt(pos) != '(') throw new IllegalArgumentException("expected '(' at " + pos);
            pos++;
            List<SExpr> items = new ArrayList<>();
            skipWs();
            while (true) {
                if (eof()) throw new IllegalArgumentException("unterminated list");
                char c = s.charAt(pos);
                if (c == ')') { pos++; return new SList(items); }
                items.add(read());
                skipWs();
            }
        }

        private SExpr readQuotedSym() {
            int start = pos;
            pos++;
            StringBuilder sb = new StringBuilder("|");
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                sb.append(c);
                if (c == '|') return new Atom(sb.toString());
            }
            throw new IllegalArgumentException("unterminated quoted symbol at " + start);
        }

        private SExpr readAtom() {
            int start = pos;
            char c = s.charAt(pos);
            if (c == '"') return readString(start);
            while (pos < s.length()) {
                char ch = s.charAt(pos);
                if (Character.isWhitespace(ch) || ch == '(' || ch == ')' || ch == ';') break;
                pos++;
            }
            return new Atom(s.substring(start, pos));
        }

        private SExpr readString(int start) {
            pos++;
            StringBuilder sb = new StringBuilder("\"");
            while (pos < s.length()) {
                char ch = s.charAt(pos++);
                if (ch == '"') {
                    if (pos < s.length() && s.charAt(pos) == '"') {
                        sb.append("\"\"");
                        pos++;
                        continue;
                    }
                    sb.append('"');
                    return new Atom(sb.toString());
                }
                sb.append(ch);
            }
            throw new IllegalArgumentException("unterminated string at " + start);
        }
    }
}
