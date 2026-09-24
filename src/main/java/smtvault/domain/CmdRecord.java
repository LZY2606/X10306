package smtvault.domain;

/**
 * One imported SMT-LIB interaction: the exact command text, its parsed form
 * (or null when malformed), the solver response text in transcript order, and
 * the parsed response form (sat / model / unsat core / unknown ...).
 */
public final class CmdRecord {
    public final int seq;
    public final int startLine;
    public final String raw;
    public final Sexp command;
    public final String parseError;
    public final String responseRaw;
    public final Sexp response;
    public final String responseError;

    public CmdRecord(int seq, int startLine, String raw, Sexp command, String parseError,
                     String responseRaw, Sexp response, String responseError) {
        this.seq = seq;
        this.startLine = startLine;
        this.raw = raw;
        this.command = command;
        this.parseError = parseError;
        this.responseRaw = responseRaw;
        this.response = response;
        this.responseError = responseError;
    }

    public String commandName() {
        if (command == null || !command.isList() || command.children().isEmpty()) {
            return null;
        }
        Sexp head = command.head();
        return head.isAtom() ? head.token() : null;
    }

    public boolean malformed() {
        return parseError != null;
    }
}
