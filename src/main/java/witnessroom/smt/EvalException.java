package witnessroom.smt;

/** Local semantic error (sort clash, unknown symbol, division by zero, ...). */
public class EvalException extends RuntimeException {
    public EvalException(String message) { super(message); }
}
