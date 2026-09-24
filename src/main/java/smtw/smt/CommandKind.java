package smtw.smt;

public enum CommandKind {
    SET_LOGIC,
    SET_OPTION,
    DECLARE_SORT,
    DEFINE_SORT,
    DECLARE_CONST,
    DECLARE_FUN,
    DEFINE_FUN,
    ASSERT,
    PUSH,
    POP,
    CHECK_SAT,
    CHECK_SAT_ASSUMING,
    GET_MODEL,
    GET_UNSAT_CORE,
    RESET,
    EXIT,
    ECHO,
    OTHER
}
