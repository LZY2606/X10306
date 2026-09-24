package smtw.smt;

import java.util.List;

/**
 * One parsed top-level SMT-LIB command. The original textual form is retained verbatim
 * (baseline requirement) and the structured s-expression is available for interpretation.
 */
public record SmtCommand(int ordinal, String raw, Sexpr.List sexpr, CommandKind kind, List<String> parseErrors) {

    public boolean wellFormed() { return parseErrors.isEmpty(); }

    public String head() {
        return sexpr.items().isEmpty() ? "" : Sexpr.token(sexpr.items().get(0));
    }

    public List<Sexpr> args() { return sexpr.args(); }
}
