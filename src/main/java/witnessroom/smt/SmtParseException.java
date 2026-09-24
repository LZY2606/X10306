package witnessroom.smt;

/** Explicit, copy-able format error with a 1-based source position. */
public class SmtParseException extends RuntimeException {
    private final int line;
    private final int col;

    public SmtParseException(String message) {
        this(message, 0, 0);
    }

    public SmtParseException(String message, int line, int col) {
        super(line > 0 ? message + " (line " + line + ", col " + col + ")" : message);
        this.line = line;
        this.col = col;
    }

    public int line() { return line; }
    public int col() { return col; }
}
