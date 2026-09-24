package smtvault.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * S-expression: either an atom (token) or a list of children.
 * Original textual representation is always retained on the root term so
 * normalization never destroys the imported representation.
 */
public final class Sexp {
    private final boolean atom;
    private final String token;
    private final List<Sexp> children;
    private final int line;

    private Sexp(boolean atom, String token, List<Sexp> children, int line) {
        this.atom = atom;
        this.token = token;
        this.children = children;
        this.line = line;
    }

    public static Sexp atom(String token, int line) {
        return new Sexp(true, token, List.of(), line);
    }

    public static Sexp list(List<Sexp> children, int line) {
        return new Sexp(false, null, children, line);
    }

    public boolean isAtom() {
        return atom;
    }

    public boolean isList() {
        return !atom;
    }

    public String token() {
        return token;
    }

    public List<Sexp> children() {
        return children;
    }

    public int line() {
        return line;
    }

    public Sexp head() {
        return children.get(0);
    }

    public boolean isCall(String keyword) {
        return isList() && !children.isEmpty() && children.get(0).isAtom()
                && children.get(0).token.equals(keyword);
    }

    public Sexp arg(int i) {
        return children.get(i + 1);
    }

    public int argc() {
        return Math.max(0, children.size() - 1);
    }

    @Override
    public String toString() {
        if (atom) {
            return token;
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < children.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(children.get(i).toString());
        }
        return sb.append(')').toString();
    }

    // ---- tokenizing + parsing ----------------------------------------

    public static final class ParseFailure extends Exception {
        public final int line;

        public ParseFailure(String message, int line) {
            super(message + " (line " + line + ")");
            this.line = line;
        }
    }

    /** Parses one complete command string, comments and ; allowed. */
    public static Sexp parse(String text) throws ParseFailure {
        List<Token> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            throw new ParseFailure("empty command", 1);
        }
        int[] pos = {0};
        Sexp result = parseTokens(tokens, pos);
        if (pos[0] != tokens.size()) {
            throw new ParseFailure("unbalanced extra tokens", tokens.get(pos[0]).line);
        }
        return result;
    }

    private record Token(String value, int line) {}

    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int line = 1;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                i++;
            } else if (c == ';') {
                while (i < n && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '|') {
                int start = i;
                int startLine = line;
                i++;
                StringBuilder sb = new StringBuilder("|");
                while (i < n && text.charAt(i) != '|') {
                    if (text.charAt(i) == '\n') {
                        line++;
                    }
                    sb.append(text.charAt(i));
                    i++;
                }
                if (i >= n) {
                    throw new RuntimeException(new ParseFailure("unterminated quoted symbol", startLine));
                }
                sb.append('|');
                i++;
                tokens.add(new Token(sb.toString(), startLine));
            } else if (c == '(' || c == ')') {
                tokens.add(new Token(String.valueOf(c), line));
                i++;
            } else {
                int startLine = line;
                StringBuilder sb = new StringBuilder();
                while (i < n) {
                    char d = text.charAt(i);
                    if (Character.isWhitespace(d) || d == '(' || d == ')') {
                        break;
                    }
                    if (d == ';') {
                        break;
                    }
                    sb.append(d);
                    i++;
                }
                tokens.add(new Token(sb.toString(), startLine));
            }
        }
        return tokens;
    }

    private static Sexp parseTokens(List<Token> tokens, int[] pos) throws ParseFailure {
        Token t = tokens.get(pos[0]++);
        if ("(".equals(t.value)) {
            List<Sexp> children = new ArrayList<>();
            while (pos[0] < tokens.size() && !")".equals(tokens.get(pos[0]).value)) {
                children.add(parseTokens(tokens, pos));
            }
            if (pos[0] >= tokens.size()) {
                throw new ParseFailure("missing closing parenthesis", t.line);
            }
            pos[0]++;
            return list(children, t.line);
        }
        if (")".equals(t.value)) {
            throw new ParseFailure("unexpected )", t.line);
        }
        return atom(t.value, t.line);
    }
}
