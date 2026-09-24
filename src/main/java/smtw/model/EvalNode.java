package smtw.model;

import java.util.List;

/**
 * One node of a model evaluation tree. Every node carries:
 *  - a stable path-based id (rootId + child path), so the same transcript/model always shows
 *    the same identity, including after export/delete/reimport;
 *  - the raw SMT term it evaluates;
 *  - a status: OK / UNDEF (partial model) / ERROR (e.g. unconvertible value) / UNSUPPORTED;
 *  - an optional unit/coordinate annotation and whether the value was normalised in the derived layer.
 */
public record EvalNode(
        String id,
        String term,
        String value,
        EvalStatus status,
        String unit,
        String canonicalUnit,
        String canonicalValue,
        String coordinateBasis,
        String diagnostic,
        List<EvalNode> children) {

    public EvalNode { children = List.copyOf(children); }

    public enum EvalStatus { OK, UNDEF, ERROR, UNSUPPORTED }

    public boolean decided() { return status == EvalStatus.OK; }
}
