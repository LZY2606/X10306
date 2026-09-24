package org.witness.smt.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One node of a model evaluation tree. {@code path} is a stable structural
 * coordinate inside a derived result (e.g. {@code a3/0/1}), used to mint
 * stable display ids that survive export and re-import.
 */
public final class EvalTree {

    public enum Status { VALUE, UNKNOWN, ERROR }

    public String label;
    public String sortText;
    public String valueText;
    public Status status = Status.VALUE;
    public String reason;
    public String sourceEntryId;
    public final List<EvalTree> children = new ArrayList<>();
    public String path = "";

    public EvalTree() {
    }

    public EvalTree(String label) {
        this.label = label;
    }

    public void assignPaths() {
        assignPaths("");
    }

    private void assignPaths(String prefix) {
        this.path = prefix;
        for (int i = 0; i < children.size(); i++) {
            children.get(i).assignPaths(prefix.isEmpty() ? String.valueOf(i) : prefix + "/" + i);
        }
    }
}
