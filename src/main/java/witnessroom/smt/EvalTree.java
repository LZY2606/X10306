package witnessroom.smt;

import java.util.List;

/**
 * One node of an evaluation tree shown in the review UI. The stable identity
 * is a path address rooted at the evaluated term, so re-evaluation against a
 * different model keeps the same identity while the value changes.
 */
public record EvalTree(String nodeId, String expr, String sort, String value,
                       String status, String diagnostic, List<EvalTree> children) {

    public static final String OK = "ok";
    public static final String UNSUPPORTED = "unsupported";
    public static final String ERROR = "error";
    public static final String UNKNOWN = "unknown";

    public EvalTree { children = children == null ? List.of() : List.copyOf(children); }

    public boolean ok() { return OK.equals(status); }
}
