package witness.sem;

import java.util.ArrayList;
import java.util.List;

/** One node of the model evaluation tree shown in the review UI. */
public final class EvalNode {
    public final String expr;
    public String result;
    public String note;
    public final List<EvalNode> children = new ArrayList<>();

    public EvalNode(String expr) { this.expr = expr; }

    public EvalNode leaf(String result) { this.result = result; return this; }
    public EvalNode note(String n) { this.note = n; return this; }
    public EvalNode add(EvalNode child) { children.add(child); return child; }

    public int leafCount() {
        if (children.isEmpty()) return 1;
        int n = 0;
        for (EvalNode c : children) n += c.leafCount();
        return n;
    }
}
