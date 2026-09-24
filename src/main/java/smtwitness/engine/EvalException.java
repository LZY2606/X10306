package smtwitness.engine;

/** Thrown when a term is outside the local semantic subset. Message is a machine-readable code. */
public class EvalException extends RuntimeException {
    private final String code;
    public EvalException(String code, String detail) {
        super(code + ": " + detail);
        this.code = code;
    }
    public String code() { return code; }
}
