package witnessroom.smt;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of importing an SMT-LIB command transcript. The raw text is immutable
 * evidence; every parsed command keeps its source position. Parse errors are
 * collected rather than aborting, so partially valid input is still auditable.
 */
public record SmtSession(String rawText, String logic, List<SmtCommand> commands,
                         List<ParseDiagnostic> diagnostics) {

    public record ParseDiagnostic(int index, int line, int col, String message, String severity) {}

    public static SmtSession parse(String rawText) {
        List<TokenLike> top = new ArrayList<>();
        List<SmtLexer.Token> tokens = SmtLexer.lex(rawText);
        List<ParseDiagnostic> diags = new ArrayList<>();
        int depth = 0;
        int startToken = 0;
        SmtLexer.Token openTok = null;
        for (int i = 0; i <= tokens.size(); i++) {
            SmtLexer.Token t = i < tokens.size() ? tokens.get(i) : null;
            if (t == null || (depth == 0 && !top.isEmpty())) {
                List<SmtLexer.Token> slice = tokens.subList(startToken, i);
                try {
                    SExpr sexpr = parseSlice(slice);
                    if (sexpr instanceof SExpr.List cmd) {
                        SmtLexer.Token first = slice.get(0);
                        top.add(new TokenLike(cmd, first.line(), first.col()));
                    } else {
                        SmtLexer.Token first = slice.get(0);
                        diags.add(new ParseDiagnostic(top.size(), first.line(), first.col(),
                                "top-level S-expressions must be parenthesised commands: "
                                        + sexpr.raw(), "error"));
                    }
                } catch (SmtParseException ex) {
                    SmtLexer.Token first = slice.get(0);
                    diags.add(new ParseDiagnostic(top.size(), first.line(), first.col(),
                            ex.getMessage(), "error"));
                }
                startToken = i;
                openTok = null;
                if (t == null) break;
            }
            if (t.isOpen()) {
                if (depth == 0) { startToken = i; openTok = t; }
                depth++;
            } else if (t.isClose()) {
                depth--;
                if (depth < 0) {
                    diags.add(new ParseDiagnostic(top.size(), t.line(), t.col(),
                            "unbalanced ')' with no matching '('", "error"));
                    depth = 0;
                    startToken = i + 1;
                }
            }
        }
        if (depth > 0) {
            diags.add(new ParseDiagnostic(top.size(), openTok != null ? openTok.line() : 0,
                    openTok != null ? openTok.col() : 0,
                    "unterminated command, missing " + depth + " ')'", "error"));
        }
        List<SmtCommand> commands = new ArrayList<>();
        String logic = null;
        for (TokenLike tl : top) {
            SmtCommand cmd = new SmtCommand(commands.size(), tl.sexpr.raw(), tl.sexpr, tl.line, tl.col);
            if ("set-logic".equals(cmd.head()) && !cmd.sexpr.args().isEmpty()
                    && cmd.sexpr.args().get(0) instanceof SExpr.Atom a) {
                logic = a.raw();
            }
            commands.add(cmd);
        }
        return new SmtSession(rawText, logic, List.copyOf(commands), List.copyOf(diags));
    }

    private record TokenLike(SExpr.List sexpr, int line, int col) {}

    private static SExpr parseSlice(List<SmtLexer.Token> slice) {
        return new SmtParser(slice).parseExternal();
    }
}
