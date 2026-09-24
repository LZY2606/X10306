package smtwitness.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed domain model for the local finite semantic subset. */
public final class SmtModel {

    public record Decl(String name, String kind, String sort, int scopeDepth) {}

    public record Assertion(String label, SExpr term, int scopeDepth, String commandId) {}

    public record Command(
            String id,
            int ordinal,
            String head,
            String raw,
            SExpr sexp,
            String name,
            String label,
            SExpr term,
            List<SExpr> assumptionAtoms
    ) {}

    public record SolverResponse(String id, int ordinal, String kind, String raw, SExpr payload) {}

    public record Session(
            String logic,
            Map<String, String> info,
            List<Command> commands,
            List<SolverResponse> responses
    ) {
        public List<Command> commandsByHead(String head) {
            List<Command> out = new ArrayList<>();
            for (Command c : commands) if (c.head().equals(head)) out.add(c);
            return out;
        }
    }

    /** Typed value in the finite semantic subset. */
    public sealed interface Value {
        record Bool(boolean v) implements Value {}
        record Int(long v) implements Value {}
        record Arr(Value defaultValue, Map<Long, Value> stores) implements Value {}
        record Unsupported(String reason) implements Value {}
    }

    public static Value boolV(boolean b) { return new Value.Bool(b); }
    public static Value intV(long v) { return new Value.Int(v); }

    /** Model entry from a get-model / define-fun response. */
    public record ModelEntry(String name, String sort, SExpr valueExpr, boolean isArray, Value constValue) {}

    public record Model(List<ModelEntry> entries) {
        public ModelEntry find(String name) {
            for (ModelEntry e : entries) if (e.name().equals(name)) return e;
            return null;
        }
    }

    /** Evaluation tree node; every displayed value carries a stable identity. */
    public record EvalNode(
            String id,
            String label,
            String status,   // OK | UNKNOWN | UNSUPPORTED
            String value,    // rendered value or null
            String reason,
            String sourceId, // direct source identity
            List<EvalNode> children
    ) {
        public static EvalNode leaf(String id, String label, String status, String value, String reason, String sourceId) {
            return new EvalNode(id, label, status, value, reason, sourceId, List.of());
        }
    }

    public record Candidate(
            String id,
            Map<String, Value> assignment,
            boolean valid,
            List<String> rejectReasons,
            EvalNode evalTree
    ) {}

    public record CheckVerdict(
            String status,   // CONFIRMED | REFUTED | UNKNOWN | UNSUPPORTED | OUT_OF_FINITE_WINDOW
            String detail,
            List<Candidate> validCandidates,
            List<Candidate> rejectedCandidates,
            List<String> unsupportedNotes
    ) {}

    public record CoreVerdict(
            String status,        // VALID_NONMINIMAL | VALID_MINIMAL | INVALID_SATISFIABLE | OUT_OF_FINITE_WINDOW | UNSUPPORTED
            boolean unsatWithinWindow,
            boolean minimal,
            List<String> labels,
            List<String> sourceCommandIds,
            String detail
    ) {}

    public record ReductionAttempt(
            String id,
            String droppedLabel,
            boolean stillUnsat,
            String outcome   // KEPT_ESSENTIAL | DROPPED_REDUNDANT | UNKNOWN
    ) {}

    /** A domain quantity keeps its raw representation plus an optional normalized derived form. */
    public record Quantity(
            String id,
            String name,
            String rawValue,
            String rawUnit,
            String coordinateBasis,
            String normalizedValue,
            String normalizedUnit,
            String diagnostic   // non-null when conversion was impossible; raw stays in place
    ) {}

    public static Map<String, Value> assignmentOf(Object... kv) {
        Map<String, Value> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Value) kv[i + 1]);
        return m;
    }
}
