package smtw.scope;

import smtw.smt.Sexpr;

/** One named-assertion binding on the stack; deeper bindings shadow earlier bindings of the same name. */
public record Binding(
        String id,
        String label,
        String assertedCommandId,
        int assertOrdinal,
        int level,
        Sexpr term) {
}
