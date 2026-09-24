package smtwitness.smtlib;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer for the SMT-LIB v2 concrete syntax subset used by the witness room.
 * Preserves the raw text of every atom so original representations survive import.
 */
public final class SmtLexer {
    private final String src;
    private int pos;

    private SmtLexer(String src) {
        this.src = src;
    }

    public static List<String> tokenize(String src) {
        return new SmtLexer(src).readAll();
    }

    private List<String> readAll() {
        List<String> tokens = new ArrayList<>();
        while (true) {
            skipWhitespaceAndComments();
            if (pos >= src.length()) {
                return tokens;
            }
            char c = src.charAt(pos);
            if (c == '(' || c == ')') {
                tokens.add(String.valueOf(c));
                pos++;
            } else if (c == '|') {
                tokens.add(readQuotedSymbol());
            } else if (c == '"') {
                tokens.add(readStringLiteral());
            } else {
                tokens.add(readBareAtom());
            }
        }
    }

    private void skipWhitespaceAndComments() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == ';') {
                while (pos < src.length() && src.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                return;
            }
        }
    }

    private String readQuotedSymbol() {
        int start = pos;
        pos++; // opening |
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '\\' && pos < src.length()) {
                pos++; // escaped char
            } else if (c == '|') {
                return src.substring(start, pos);
            }
        }
        throw new SmtParseException("unterminated quoted symbol starting at offset " + start);
    }

    private String readStringLiteral() {
        int start = pos;
        pos++; // opening "
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                if (pos < src.length() && src.charAt(pos) == '"') {
                    pos++; // escaped quote ""
                } else {
                    return src.substring(start, pos);
                }
            }
        }
        throw new SmtParseException("unterminated string literal starting at offset " + start);
    }

    private String readBareAtom() {
        int start = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (Character.isWhitespace(c) || c == '(' || c == ')' || c == ';') {
                break;
            }
            pos++;
        }
        if (pos == start) {
            throw new SmtParseException("unexpected character '" + src.charAt(pos) + "' at offset " + pos);
        }
        return src.substring(start, pos);
    }
}
