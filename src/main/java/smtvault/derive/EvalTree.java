package smtvault.derive;

import java.util.ArrayList;
import java.util.List;
import smtvault.domain.Sexp;

/** Result of evaluating one term: a value with provenance and child nodes. */
public final class EvalTree {
    public enum Status { SATISFIED, VIOLATED, UNKNOWN, ERROR }

    public final String id;
    public final Sexp term;
    public final String rawText;
    public final String value;
    public final Status status;
    public final String reason;
    public final String basis; // unit / coordinate basis annotation
    public final List<EvalTree> children = new ArrayList<>();

    public EvalTree(String id, Sexp term, String rawText, String value, Status status,
                    String reason, String basis) {
        this.id = id;
        this.term = term;
        this.rawText = rawText;
        this.value = value;
        this.status = status;
        this.reason = reason;
        this.basis = basis;
    }

    public boolean decided() {
        return status == Status.SATISFIED || status == Status.VIOLATED;
    }
}
