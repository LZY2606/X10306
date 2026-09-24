package org.witness.smt.model;

import org.witness.smt.parse.SExpr;

import java.util.ArrayList;
import java.util.List;

/** One node of a model-evaluation tree.  Ids are stable positional paths. */
public final class EvalNode {
    public enum Status { OK, UNMODELED, UNSUPPORTED_THEORY, UNRESOLVED_LABEL, BAD_SORT, UNKNOWN }

    public final String id;
    public final String expr;
    public final String op;
    public Value value;
    public Status status = Status.OK;
    public String reason = "";
    public String unit = "";
    public final List<EvalNode> children = new ArrayList<>();

    public EvalNode(String id, SExpr expr) {
        this.id = id;
        this.expr = expr.raw();
        this.op = expr instanceof SExpr.List l ? l.headName() : "<atom>";
    }

    public String statusName() { return status.name(); }

    public boolean conclusiveFalse() {
        return status == Status.OK && value instanceof Value.Bool b && !b.b();
    }

    public boolean hasUnsupported() {
        if (status == Status.UNSUPPORTED_THEORY) return true;
        for (EvalNode c : children) if (c.hasUnsupported()) return true;
        return false;
    }

    public boolean hasUnknown() {
        if (status == Status.UNMODELED || status == Status.UNKNOWN || status == Status.UNRESOLVED_LABEL) return true;
        for (EvalNode c : children) if (c.hasUnknown()) return true;
        return false;
    }

    /** Collect unmodelled boolean leaves: free boolean constants that the model left open. */
    public void collectBooleanUnknowns(List<String> out) {
        if (status == Status.UNMODELED && expr.startsWith("|") == false
                && value == null && couldBeBoolAtom()) out.add(expr);
        for (EvalNode c : children) c.collectBooleanUnknowns(out);
    }

    private boolean couldBeBoolAtom() { return true; }
}
