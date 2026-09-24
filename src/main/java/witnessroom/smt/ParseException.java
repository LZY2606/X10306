package witnessroom.smt;

public class ParseException extends Exception {
    public final int line;
    public final int col;
    public ParseException(String msg, int line, int col) {
        super(msg + " (line " + line + ", col " + col + ")");
        this.line = line;
        this.col = col;
    }
}
