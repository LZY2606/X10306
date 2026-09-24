package com.witness.smt.sexpr;

/**
 * One SMT-LIB command. The original text is preserved verbatim ({@link #raw}).
 * {@code sort} is only set for declare-fun/declare-const.
 */
public record Command(
        int index,
        Kind kind,
        String raw,
        SExpr.List form,
        String name,      // declared symbol / :named label
        String sort,      // raw sort text for declarations
        SExpr term,       // asserted term / defined body (may be null)
        SExpr.List assuming // literals of check-sat-assuming (may be null)
) {
    public enum Kind {
        SET_LOGIC, DECLARE_FUN, DECLARE_CONST, DEFINE_FUN, ASSERT,
        PUSH, POP, CHECK_SAT, CHECK_SAT_ASSUMING, GET_MODEL, GET_UNSAT_CORE, OTHER
    }

    public boolean isCheck() { return kind == Kind.CHECK_SAT || kind == Kind.CHECK_SAT_ASSUMING; }

    /** Stable content identity: kind + canonical raw text. */
    public String contentKey() { return kind + ":" + raw; }
}
