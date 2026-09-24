package smtroom.core;

import java.util.ArrayList;
import java.util.List;

/** Evaluation tree node for the model-evaluation-tree view. */
public final class EvalNode {
    public final String expr;
    public final String value;
    public final String status; // OK | UNKNOWN | UNSUPPORTED
    public final List<EvalNode> children = new ArrayList<>();

    public EvalNode(String expr, String value, String status) {
        this.expr = expr; this.value = value; this.status = status;
    }

    public static String statusOf(Value v) {
        if (v instanceof Value.VUnknown) return "UNKNOWN";
        if (v instanceof Value.VUnsupported) return "UNSUPPORTED";
        return "OK";
    }

    /** Flatten for simple SVG/text rendering: [depth, expr, value, status]. */
    public List<String[]> flatten() {
        List<String[]> out = new ArrayList<>();
        flattenInto(0, out);
        return out;
    }

    private void flattenInto(int depth, List<String[]> out) {
        out.add(new String[]{String.valueOf(depth), expr, value, status});
        for (EvalNode c : children) c.flattenInto(depth + 1, out);
    }
}
