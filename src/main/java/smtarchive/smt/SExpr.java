package smtarchive.smt;

import java.util.ArrayList;
import java.util.List;

/**
 * S-expressions with source positions and verbatim text. The parser is deliberately
 * permissive: malformed input yields a structured FormatError rather than an exception
 * aborting the import, so reviewers always keep the raw command stream.
 */
public sealed interface SExpr permits SExpr.Atom, SExpr.List {
    int startOffset();
    int endOffset();
    int line();

    record Atom(String token, int startOffset, int endOffset, int line) implements SExpr {}
    record List(java.util.List<SExpr> items, int startOffset, int endOffset, int line) implements SExpr {
        public SExpr head() { return items.isEmpty() ? null : items.get(0); }
        public String headToken() {
            SExpr h = head();
            return h instanceof Atom a ? a.token() : null;
        }
    }

    record FormatError(String message, int offset, int line) {}

    final class Parser {
        private final String src;
        private int pos = 0;
        private int line = 1;
        private final List<FormatError> errors = new ArrayList<>();

        public Parser(String src) { this.src = src; }

        public static List<SExpr> parseAll(String src, List<FormatError> errorsOut) {
            Parser p = new Parser(src);
            List<SExpr> out = new ArrayList<>();
            p.skipWsComments();
            while (p.pos < p.src.length()) {
                out.add(p.parseExpr());
                p.skipWsComments();
            }
            errorsOut.addAll(p.errors);
            return out;
        }

        public static String unquote(String quoted) {
            if (quoted.length() >= 2 && quoted.startsWith("|") && quoted.endsWith("|")) {
                return quoted.substring(1, quoted.length() - 1);
            }
            if (quoted.length() >= 2 && quoted.startsWith("\"") && quoted.endsWith("\"")) {
                return quoted.substring(1, quoted.length() - 1).replace("\"\"", "\"");
            }
            return quoted;
        }

        private SExpr parseExpr() {
            skipWsComments();
            if (pos >= src.length()) {
                errors.add(new FormatError("unexpected end of input", pos, line));
                return new Atom("", pos, pos, line);
            }
            char c = src.charAt(pos);
            if (c == '(') return parseList();
            return parseAtom();
        }

        private SExpr parseList() {
            int start = pos, startLine = line;
            pos++;
            List<SExpr> items = new ArrayList<>();
            while (true) {
                skipWsComments();
                if (pos >= src.length()) {
                    errors.add(new FormatError("unclosed parenthesis", start, startLine));
                    return new List(items, start, pos, startLine);
                }
                char c = src.charAt(pos);
                if (c == ')') { pos++; return new List(items, start, pos, startLine); }
                items.add(parseExpr());
            }
        }

        private SExpr parseAtom() {
            int start = pos, startLine = line;
            if (src.charAt(pos) == '|') {
                pos++;
                while (pos < src.length() && src.charAt(pos) != '|') { if (src.charAt(pos) == '\n') line++; pos++; }
                if (pos >= src.length()) errors.add(new FormatError("unclosed quoted symbol", start, startLine));
                else pos++;
            } else if (src.charAt(pos) == '"') {
                pos++;
                while (pos < src.length()) {
                    char c = src.charAt(pos);
                    if (c == '"') {
                        if (pos + 1 < src.length() && src.charAt(pos + 1) == '"') { pos += 2; continue; }
                        pos++;
                        break;
                    }
                    if (c == '\n') line++;
                    pos++;
                }
                if (pos > src.length() || (pos == src.length() && src.charAt(pos - 1) != '"'))
                    errors.add(new FormatError("unclosed string literal", start, startLine));
            } else {
                while (pos < src.length() && !isDelim(src.charAt(pos))) {
                    if (src.charAt(pos) == '\n') line++;
                    pos++;
                }
            }
            return new Atom(src.substring(start, Math.min(pos, src.length())), start, pos, startLine);
        }

        private void skipWsComments() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) { if (c == '\n') line++; pos++; }
                else if (c == ';') { while (pos < src.length() && src.charAt(pos) != '\n') pos++; }
                else break;
            }
        }

        private boolean isDelim(char c) {
            return Character.isWhitespace(c) || c == '(' || c == ')' || c == ';';
        }
    }
}
