package com.witness.smt.check;

import com.witness.smt.sexpr.SExpr;

import java.util.List;

/** Result of evaluating one term: a value, partial/unknown, unsupported theory/feature, or error. */
public sealed interface EvalOutcome permits EvalOutcome.Ok, EvalOutcome.Unknown, EvalOutcome.Unsupported, EvalOutcome.Error {

    record Ok(Value value, Trace trace) implements EvalOutcome {}
    record Unknown(String reason, Trace trace) implements EvalOutcome {}
    record Unsupported(String feature, Trace trace) implements EvalOutcome {}
    record Error(String reason, Trace trace) implements EvalOutcome {}

    /** Evaluation tree node: term text, verdict and child nodes, used by the review UI. */
    record Trace(SExpr term, String verdict, String detail, List<Trace> children) {
        public static Trace leaf(SExpr t, String verdict, String detail) {
            return new Trace(t, verdict, detail, List.of());
        }
        public static Trace node(SExpr t, String verdict, String detail, List<Trace> kids) {
            return new Trace(t, verdict, detail, kids == null ? List.of() : List.copyOf(kids));
        }
        public String termText() { return term == null ? "?" : term.raw(); }
    }
}
