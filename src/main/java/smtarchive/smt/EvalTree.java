package smtarchive.smt;

import java.util.ArrayList;
import java.util.List;

/** One node of a model evaluation tree: term text, resolved value, and diagnostics. */
public record EvalNode(String nodeId, String termText, String valueText, boolean supported,
                       List<String> diagnostics, List<EvalNode> children) {
    public EvalNode { children = children == null ? new ArrayList<>() : children; }

    public static EvalNode unsupported(String id, String termText, List<String> diag, List<EvalNode> kids) {
        return new EvalNode(id, termText, "#UNKNOWN", false, diag, kids);
    }
}
