package witness.smt;

import java.util.ArrayList;
import java.util.List;

/** Minimal s-expression used by the SMT-LIB reader. */
public sealed interface Sx permits Sx.Atom, Sx.List {
    record Atom(String text, int line, int col) implements Sx {}
    record List(java.util.List<Sx> items, int line, int col) implements Sx {}

    final class Reader {
        private final String src;
        private int pos = 0;
        private int line = 1;
        private int col = 1;

        public Reader(String src) { this.src = src; }

        public static List<Sx> readAll(String src) {
            Reader r = new Reader(src);
            List<Sx> out = new ArrayList<>();
            r.ws();
            while (r.pos < r.src.length()) {
                out.add(r.sexpr());
                r.ws();
            }
            return out;
        }

        private void ws() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ';') {
                    while (pos < src.length() && src.charAt(pos) != '\n') adv();
                } else if (Character.isWhitespace(c)) {
                    adv();
                } else break;
            }
        }

        private void adv() {
            if (src.charAt(pos) == '\n') { line++; col = 1; } else col++;
            pos++;
        }

        private Sx sexpr() {
            ws();
            int sl = line, sc = col;
            char c = src.charAt(pos);
            if (c == '(') {
                adv();
                List<Sx> items = new ArrayList<>();
                while (true) {
                    ws();
                    if (pos >= src.length()) throw new SmtException("unbalanced parenthesis: missing ')'", sl, sc);
                    if (src.charAt(pos) == ')') { adv(); return new List(items, sl, sc); }
                    items.add(sexpr());
                }
            }
            if (c == ')') throw new SmtException("unexpected ')'", line, col);
            return new List(java.util.List.of(readAtomRaw()), sl, sc);
        }

        /** Reads one atom respecting |quoted| and "string" syntax. */
        public String readAtomRaw() {
            int sl = line, sc = col;
            char c = src.charAt(pos);
            if (c == '|') {
                int start = pos;
                adv();
                while (pos < src.length() && src.charAt(pos) != '|') adv();
                if (pos >= src.length()) throw new SmtException("unterminated quoted symbol", sl, sc);
                adv();
                return src.substring(start, pos);
            }
            if (c == '"') {
                int start = pos;
                adv();
                while (pos < src.length()) {
                    if (src.charAt(pos) == '"') {
                        adv();
                        if (pos < src.length() && src.charAt(pos) == '"') { adv(); continue; }
                        return src.substring(start, pos);
                    }
                    adv();
                }
                throw new SmtException("unterminated string literal", sl, sc);
            }
            int start = pos;
            while (pos < src.length()) {
                char d = src.charAt(pos);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == ';') break;
                adv();
            }
            return src.substring(start, pos);
        }
    }

    final class SmtException extends RuntimeException {
        public final int line, col;
        public SmtException(String msg, int line, int col) { super(msg + " (line " + line + ":" + col + ")"); this.line = line; this.col = col; }
    }
}
