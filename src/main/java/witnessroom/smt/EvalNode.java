package witnessroom.smt;

import java.util.ArrayList;
import java.util.List;

/** One node of a model-evaluation tree; every displayed value has a stable path id. */
public final class EvalNode {
    public enum Status { VALUE, UNKNOWN, UNSUPPORTED }

    public final String path;        // stable identity, e.g. /0/2/1
    public final String term;        // rendered term
    public Status status;
    public Value value;              // when status == VALUE
    public String reason;            // diagnostic when not VALUE
    public String theory;            // offending theory label when UNSUPPORTED
    public final List<EvalNode> children = new ArrayList<>();

    public EvalNode(String path, String term) {
        this.path = path;
        this.term = term;
    }
}
