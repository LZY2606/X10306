package witnessroom.smt;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexer for the local SMT-LIB text subset. Preserves every token verbatim,
 * understands quoted symbols |...|, string literals "..." and line comments.
 * Each token carries its character offset in the original stream.
 */
public final class SmtLexer {

    public record Token(String text, int offset, int line, int col) {
        boolean isOpen() { return text.equals("("); }
        boolean isClose() { return text.equals(")"); }
    }

    private SmtLexer() {}

    public static List<Token> lex(String input) {
        List<Token> tokens = new ArrayList<>();
        int n = input.length();
        int i = 0;
        int line = 1;
        int col = 1;
        while (i < n) {
            char c = input.charAt(i);
            if (c == ';') {
                while (i < n && input.charAt(i) != '\n') { i++; col++; }
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (c == '\n') { line++; col = 1; } else { col++; }
                i++;
                continue;
            }
            int start = i;
            int startLine = line;
            int startCol = col;
            if (c == '(' || c == ')') {
                tokens.add(new Token(String.valueOf(c), start, startLine, startCol));
                i++; col++;
                continue;
            }
            if (c == '|') {
                i++; col++;
                while (i < n && input.charAt(i) != '|') {
                    if (input.charAt(i) == '\n') { line++; col = 1; } else { col++; }
                    i++;
                }
                if (i >= n) {
                    throw new SmtParseException("unterminated quoted symbol starting at line " + startLine);
                }
                i++; col++;
                tokens.add(new Token(input.substring(start, i), start, startLine, startCol));
                continue;
            }
            if (c == '"') {
                i++; col++;
                while (i < n) {
                    char d = input.charAt(i);
                    if (d == '"') {
                        if (i + 1 < n && input.charAt(i + 1) == '"') { i += 2; col += 2; continue; }
                        i++; col++;
                        break;
                    }
                    if (d == '\n') { line++; col = 1; } else { col++; }
                    i++;
                }
                tokens.add(new Token(input.substring(start, i), start, startLine, startCol));
                continue;
            }
            while (i < n) {
                char d = input.charAt(i);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == ';') break;
                i++; col++;
            }
            tokens.add(new Token(input.substring(start, i), start, startLine, startCol));
        }
        return tokens;
    }
}
