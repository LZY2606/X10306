package smtroom.smt;

import smtroom.sexpr.SExpr;
import java.util.ArrayList;
import java.util.List;

/** A parsed SMT-LIB session script: ordered commands, each with verbatim raw text. */
public record Script(List<Command> commands, String logic) {

    public static Script parse(String src) {
        List<String> spans = topLevelSpans(src);
        List<Command> cmds = new ArrayList<>();
        String logic = null;
        for (int i = 0; i < spans.size(); i++) {
            String raw = spans.get(i);
            SExpr e = SExpr.parse(raw);
            Command c = new Command(i, Command.Kind.of(e), raw, e);
            if (c.kind() == Command.Kind.SET_LOGIC && e.size() >= 2) logic = e.get(1).atom();
            cmds.add(c);
        }
        return new Script(List.copyOf(cmds), logic);
    }

    /** Split source into top-level s-expr raw spans, preserving exact text. */
    static List<String> topLevelSpans(String src) {
        List<String> out = new ArrayList<>();
        int n = src.length(), i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == ';') { while (i < n && src.charAt(i) != '\n') i++; continue; }
            if (c != '(') throw new SExpr.SmtParseException("top-level command must start with '(' at offset " + i);
            int start = i, depth = 0;
            boolean done = false;
            while (i < n && !done) {
                char d = src.charAt(i);
                if (d == ';') { while (i < n && src.charAt(i) != '\n') i++; continue; }
                if (d == '|') { int j = src.indexOf('|', i + 1); if (j < 0) throw new SExpr.SmtParseException("unclosed '|'"); i = j + 1; continue; }
                if (d == '"') { i++; while (i < n && src.charAt(i) != '"') i++; i++; continue; }
                if (d == '(') depth++;
                if (d == ')') { depth--; if (depth == 0) { i++; done = true; continue; } }
                i++;
            }
            if (depth != 0) throw new SExpr.SmtParseException("unclosed command starting at offset " + start);
            out.add(src.substring(start, i));
        }
        return out;
    }
}
