package witnessroom.smt;

import java.util.List;

/**
 * One parsed command of an SMT-LIB session. The raw source text and the
 * source span (line/col) are always preserved; normalization happens only
 * in derived layers.
 */
public record Command(
        int index,
        String raw,
        int line,
        int col,
        Kind kind,
        SExpr ast,
        String name,               // assertion name from (! t :named n), else null
        SExpr term,                // assertion term, else null
        List<SExpr> assumptions,   // check-sat-assuming literals, else null
        int levels,                // push/pop levels
        String declName,           // declare-const / declare-fun / define-fun name
        String sort,               // declared sort (rendered), else null
        boolean supported,         // inside the local finite semantic subset
        String diagnostic          // reason when not fully supported, else null
) {
    public enum Kind {
        SET_LOGIC, DECLARE_CONST, DECLARE_FUN, DEFINE_FUN, ASSERT,
        PUSH, POP, CHECK_SAT, CHECK_SAT_ASSUMING,
        GET_MODEL, GET_UNSAT_CORE, GET_VALUE, EXIT, UNKNOWN
    }
}
