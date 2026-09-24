package org.witness.smt;

import java.util.ArrayList;
import java.util.List;

/**
 * S-expressions in the local SMT-LIB subset. Atoms keep their raw spelling;
 * lists preserve element order. The parser reports explicit format errors
 * with 1-based line/column positions.
 */
public final class SExpr {

    private final String atom;
    private final List<SExpr> items;
    public final int line;
    public final int col;

    private SExpr(String atom, List<SExpr> items, int line, int col) {
        this.atom = atom;
        this.items = items;
        this.line = line;
        this.col = col;
    }

    public static SExpr atom(String text, int line, int col) {
        return new SExpr(text, null, line, col);
    }

    public static SExpr list(List<SExpr> items, int line, int col) {
        return new SExpr(null, List.copyOf(items), line, col);
    }

    public boolean isList() {
        return items != null;
    }

    public boolean isAtom() {
        return items == null;
    }

    public String atom() {
        if (items != null) {
            throw new IllegalStateException("not an atom at " + position());
        }
        return atom;
    }

    public List<SExpr> items() {
        if (items == null) {
            throw new IllegalStateException("not a list at " + position());
        }
        return items;
    }

    /** Head symbol of a parenthesised form, e.g. {@code assert} from {@code (assert ...)}. */
    public String head() {
        if (items == null || items.isEmpty() || items.get(0).items != null) {
            return null;
        }
        return items.get(0).atom;
    }

    public SExpr arg(int index) {
        return items.get(index + 1);
    }

    public int arity() {
        return items == null ? 0 : items.size() - 1;
    }

    public String position() {
        return line + ":" + col;
    }

    @Override
    public String toString() {
        if (items == null) {
            return atom;
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(items.get(i));
        }
        return sb.append(')').toString();
    }

    /** Parses every top-level form in the source. */
    public static List<SExpr> parseAll(String src) {
        Tokenizer t = new Tokenizer(src);
        List<SExpr> out = new ArrayList<>();
        while (true) {
            SExpr e = t.parseForm(false);
            if (e == null) {
                return out;
            }
            out.add(e);
        }
    }

    /** Parses a single top-level form; fails when trailing forms exist. */
    public static SExpr parseOne(String src) {
        Tokenizer t = new Tokenizer(src);
        SExpr e = t.parseForm(true);
        t.skipWhitespaceAndComments();
        SExpr trailing = t.pos >= src.length() ? null : t.parseForm(false);
        if (trailing != null) {
            throw new FormatError("expected a single form but found another at " + trailing.position(), trailing.line, trailing.col);
        }
        return e;
    }

    public static final class FormatError extends RuntimeException {
        public final int line;
        public final int col;

        public FormatError(String message, int line, int col) {
            super(message);
            this.line = line;
            this.col = col;
        }
    }

    private static final class Tokenizer {
        private final String src;
        private int pos = 0;
        private int line = 1;
        private int col = 1;

        Tokenizer(String src) {
            this.src = src;
        }

        private void advance() {
            char c = src.charAt(pos);
            pos++;
            if (c == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }

        private void skipWhitespaceAndComments() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) {
                    advance();
                } else if (c == ';') {
                    while (pos < src.length() && src.charAt(pos) != '\n') {
                        advance();
                    }
                } else {
                    break;
                }
            }
        }

        private String readToken() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c) || c == '(' || c == ')') {
                    break;
                }
                advance();
            }
            return src.substring(start, pos);
        }

        private String readString() {
            // opening quote already consumed
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw new FormatError("unterminated string literal", line, col);
                }
                char c = src.charAt(pos);
                if (c == '"') {
                    advance();
                    if (pos < src.length() && src.charAt(pos) == '"') {
                        sb.append('"');
                        advance();
                    } else {
                        return sb.toString();
                    }
                } else {
                    sb.append(c);
                    advance();
                }
            }
        }

        private String readQuotedSymbol() {
            // opening bar already consumed
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) {
                    throw new FormatError("unterminated quoted symbol", line, col);
                }
                char c = src.charAt(pos);
                if (c == '|') {
                    advance();
                    return sb.toString();
                }
                sb.append(c);
                advance();
            }
        }

        SExpr parseForm(boolean required) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                if (required) {
                    throw new FormatError("expected an S-expression but reached end of input", line, col);
                }
                return null;
            }
            int startLine = line;
            int startCol = col;
            char c = src.charAt(pos);
            if (c == ')') {
                throw new FormatError("unexpected ')' at " + line + ":" + col, line, col);
            }
            if (c == '(') {
                advance();
                List<SExpr> items = new ArrayList<>();
                while (true) {
                    skipWhitespaceAndComments();
                    if (pos >= src.length()) {
                        throw new FormatError("unterminated list opened at " + startLine + ":" + startCol, startLine, startCol);
                    }
                    if (src.charAt(pos) == ')') {
                        advance();
                        return SExpr.list(items, startLine, startCol);
                    }
                    items.add(parseForm(true));
                }
            }
            if (c == '"') {
                advance();
                return SExpr.atom("\"" + readString() + "\"", startLine, startCol);
            }
            if (c == '|') {
                advance();
                return SExpr.atom("|" + readQuotedSymbol() + "|", startLine, startCol);
            }
            String token = readToken();
            if (token.isEmpty()) {
                throw new FormatError("empty token at " + startLine + ":" + startCol, startLine, startCol);
            }
            return SExpr.atom(token, startLine, startCol);
        }
    }
}
