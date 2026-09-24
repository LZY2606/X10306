package app.smt;

import java.util.ArrayList;
import java.util.List;

public sealed interface SExpr permits SExpr.Atom, SExpr.Lst {
    record Atom(String token) implements SExpr {
    }

    record Lst(List<SExpr> items) implements SExpr {
        public SExpr head() {
            return items.isEmpty() ? new Atom("") : items.get(0);
        }
    }

    static String headName(SExpr expr) {
        if (expr instanceof Lst(List<SExpr> items)
                && !items.isEmpty()
                && items.get(0) instanceof Atom(String name)) {
            return name;
        }
        return "";
    }

    static List<SExpr> parseTopLevel(String text) {
        List<SExpr> results = new ArrayList<>();
        ParserState state = new ParserState(text);
        state.skipWhitespaceAndComments();
        while (!state.eof()) {
            results.add(state.parseExpression());
            state.skipWhitespaceAndComments();
        }
        return results;
    }

    static SExpr parseOne(String text) {
        ParserState state = new ParserState(text);
        state.skipWhitespaceAndComments();
        return state.parseExpression();
    }

    final class ParserState {
        private final String text;
        private int pos;

        private ParserState(String text) {
            this.text = text;
        }

        private boolean eof() {
            return pos >= text.length();
        }

        private void skipWhitespaceAndComments() {
            while (pos < text.length()) {
                char ch = text.charAt(pos);
                if (Character.isWhitespace(ch)) {
                    pos++;
                } else if (ch == ';') {
                    while (pos < text.length() && text.charAt(pos) != '\n') {
                        pos++;
                    }
                } else {
                    break;
                }
            }
        }

        private SExpr parseExpression() {
            skipWhitespaceAndComments();
            if (eof()) {
                throw new IllegalArgumentException("unexpected end of SMT expression");
            }
            char ch = text.charAt(pos);
            if (ch == '(') {
                return parseList();
            }
            if (ch == ')') {
                throw new IllegalArgumentException("unexpected ')' at position " + pos);
            }
            return parseAtom();
        }

        private SExpr parseList() {
            int start = pos;
            pos++;
            List<SExpr> items = new ArrayList<>();
            while (true) {
                skipWhitespaceAndComments();
                if (eof()) {
                    throw new IllegalArgumentException("unclosed list starting at position " + start);
                }
                if (text.charAt(pos) == ')') {
                    pos++;
                    return new Lst(items);
                }
                items.add(parseExpression());
            }
        }

        private SExpr parseAtom() {
            int start = pos;
            char first = text.charAt(pos);
            if (first == '"') {
                pos++;
                while (pos < text.length()) {
                    char ch = text.charAt(pos++);
                    if (ch == '"') {
                        if (pos < text.length() && text.charAt(pos) == '"') {
                            pos++;
                        } else {
                            return new Atom(text.substring(start, pos));
                        }
                    }
                }
                throw new IllegalArgumentException("unterminated string literal");
            }
            if (first == '|') {
                pos++;
                while (pos < text.length() && text.charAt(pos) != '|') {
                    pos++;
                }
                if (eof()) {
                    throw new IllegalArgumentException("unterminated quoted symbol");
                }
                pos++;
                return new Atom(text.substring(start, pos));
            }
            while (pos < text.length()) {
                char ch = text.charAt(pos);
                if (Character.isWhitespace(ch) || ch == '(' || ch == ')') {
                    break;
                }
                pos++;
            }
            return new Atom(text.substring(start, pos));
        }
    }
}
