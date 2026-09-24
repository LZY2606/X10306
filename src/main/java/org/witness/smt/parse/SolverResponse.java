package org.witness.smt.parse;

import java.util.ArrayList;
import java.util.List;

/** Parsed solver response: sat/unsat/unknown, a model, or an unsat core. */
public record SolverResponse(int index, String raw, String kind, List<String> coreLabels, SExpr modelExpr) {

    public static SolverResponse parse(int index, String raw) {
        String trimmed = raw.trim();
        if (trimmed.equals("sat") || trimmed.equals("unsat") || trimmed.equals("unknown")) {
            return new SolverResponse(index, raw, trimmed, List.of(), null);
        }
        if (trimmed.startsWith("(")) {
            List<SExpr> all;
            try {
                all = SmtParser.parseAll(trimmed);
            } catch (SmtParser.ParseFailure f) {
                return new SolverResponse(index, raw, "error", List.of(), null);
            }
            if (all.size() == 1 && all.get(0) instanceof SExpr.List l) {
                if (l.headName().equals("model"))
                    return new SolverResponse(index, raw, "model", List.of(), l);
                // get-unsat-core prints (label ...)
                List<String> labels = new ArrayList<>();
                boolean allAtoms = !l.items().isEmpty();
                for (SExpr x : l.items()) {
                    if (x instanceof SExpr.Atom a) labels.add(a.token());
                    else { allAtoms = false; break; }
                }
                if (allAtoms) return new SolverResponse(index, raw, "core", labels, null);
            }
        }
        if (trimmed.startsWith("(error") || trimmed.startsWith("success"))
            return new SolverResponse(index, raw, trimmed.startsWith("(error") ? "error" : "success", List.of(), null);
        return new SolverResponse(index, raw, "unknown-text", List.of(), null);
    }
}
