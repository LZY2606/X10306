package smtarchive.smt;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed transcript: raw text, ordered commands (malformed ones retained) and
 * structured format errors. Parsing never destroys the input.
 */
public record SmtSession(String rawText, List<RawEntry> entries, List<SExpr.FormatError> formatErrors,
                         String declaredLogic) {

    public record RawEntry(int sequence, String rawText, int startLine, Command command) {}

    public static SmtSession parse(String rawText) {
        List<SExpr.FormatError> errors = new ArrayList<>();
        List<SExpr> exprs = SExpr.Parser.parseAll(rawText, errors);
        List<RawEntry> entries = new ArrayList<>();
        String logic = null;
        int seq = 0;
        for (SExpr e : exprs) {
            Command c = Command.Builder.from(e);
            if (c instanceof Command.SetLogic sl) logic = sl.logic();
            int startLine = e instanceof SExpr.Atom a ? a.line()
                    : e instanceof SExpr.List l ? l.line() : 1;
            entries.add(new RawEntry(seq++, e.toString(), startLine, c));
        }
        return new SmtSession(rawText, entries, errors, logic);
    }

    public boolean hasFormatErrors() { return !formatErrors.isEmpty(); }
}
