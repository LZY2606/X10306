package smtwitness.smtlib;

/** Raised on malformed SMT-LIB input; position text is kept for diagnostics. */
public class SmtParseException extends RuntimeException {
    public SmtParseException(String message) {
        super(message);
    }
}
