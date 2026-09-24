package smtroom.smt;

import smtroom.sexpr.SExpr;

/** One SMT-LIB command. Raw source text is always preserved verbatim. */
public record Command(int index, Kind kind, String raw, SExpr expr) {

    public enum Kind {
        SET_LOGIC, DECLARE_CONST, DECLARE_FUN, DEFINE_FUN, ASSERT,
        PUSH, POP, CHECK_SAT, CHECK_SAT_ASSUMING, GET_MODEL, GET_UNSAT_CORE,
        GET_VALUE, SET_OPTION, SET_INFO, EXIT, OTHER;

        public static Kind of(SExpr e) {
            if (!(e instanceof SExpr.ListExpr) || e.size() == 0) return OTHER;
            return switch (e.head()) {
                case "set-logic" -> SET_LOGIC;
                case "declare-const" -> DECLARE_CONST;
                case "declare-fun" -> DECLARE_FUN;
                case "define-fun" -> DEFINE_FUN;
                case "assert" -> ASSERT;
                case "push" -> PUSH;
                case "pop" -> POP;
                case "check-sat" -> CHECK_SAT;
                case "check-sat-assuming" -> CHECK_SAT_ASSUMING;
                case "get-model" -> GET_MODEL;
                case "get-unsat-core" -> GET_UNSAT_CORE;
                case "get-value" -> GET_VALUE;
                case "set-option" -> SET_OPTION;
                case "set-info" -> SET_INFO;
                case "exit" -> EXIT;
                default -> OTHER;
            };
        }
    }
}
