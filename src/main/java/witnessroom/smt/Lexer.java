package witnessroom.smt;

import java.util.ArrayList;
import java.util.List;

/** Tokenizer for the SMT-LIB surface syntax. Keeps line/col for diagnostics. */
public final class Lexer {
    public record Tok(String text, int line, int col, int offset) {}

    public static List<Tok> tokenize(String src) throws ParseException {
        List<Tok> out = new ArrayList<>();
        int i = 0, line = 1, col = 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\n') { line++; col = 1; i++; continue; }
            if (Character.isWhitespace(c)) { col++; i++; continue; }
            if (c == ';') { while (i < n && src.charAt(i) != '\n') { i++; col++; } continue; }
            if (c == '(' || c == ')') {
                out.add(new Tok(String.valueOf(c), line, col, i));
                i++; col++; continue;
            }
            if (c == '|') {
                int startLine = line, startCol = col, start = i;
                StringBuilder sb = new StringBuilder();
                i++; col++;
                boolean closed = false;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '|') { closed = true; i++; col++; break; }
                    sb.append(d);
                    if (d == '\n') { line++; col = 1; } else col++;
                    i++;
                }
                if (!closed) throw new ParseException("unterminated quoted symbol", startLine, startCol);
                out.add(new Tok("|" + sb + "|", startLine, startCol, start));
                continue;
            }
            if (c == '"') {
                int startLine = line, startCol = col, start = i;
                StringBuilder sb = new StringBuilder();
                i++; col++;
                boolean closed = false;
                while (i < n) {
                    char d = src.charAt(i);
                    if (d == '"') {
                        if (i + 1 < n && src.charAt(i + 1) == '"') { sb.append('"'); i += 2; col += 2; continue; }
                        closed = true; i++; col++; break;
                    }
                    sb.append(d);
                    if (d == '\n') { line++; col = 1; } else col++;
                    i++;
                }
                if (!closed) throw new ParseException("unterminated string literal", startLine, startCol);
                out.add(new Tok("\"" + sb + "\"", startLine, startCol, start));
                continue;
            }
            int start = i, startCol = col, startLine = line;
            while (i < n) {
                char d = src.charAt(i);
                if (Character.isWhitespace(d) || d == '(' || d == ')' || d == ';') break;
                i++; col++;
            }
            out.add(new Tok(src.substring(start, i), startLine, startCol, start));
        }
        return out;
    }
}
