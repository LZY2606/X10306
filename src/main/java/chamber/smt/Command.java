package chamber.smt;

import chamber.sexpr.Sexpr;
import java.util.List;

/** One SMT-LIB command. The raw command stream is preserved verbatim in the store;
 *  this model is the parsed view used by the replay engine. */
public sealed interface Command {

    record SetLogic(String logic) implements Command {}
    record SetInfo(String key, String value) implements Command {}
    record SetOption(String key, String value) implements Command {}
    record DeclareConst(String name, Sexpr sort) implements Command {}
    record DeclareFun(String name, List<Sexpr> argSorts, Sexpr sort) implements Command {}
    record DefineFun(String name, List<Sexpr> args, Sexpr sort, Sexpr body) implements Command {}
    /** name is non-null when asserted with (! term :named n). */
    record AssertCmd(Sexpr term, String name) implements Command {}
    record Push(int levels) implements Command {}
    record Pop(int levels) implements Command {}
    record CheckSat() implements Command {}
    record CheckSatAssuming(List<Sexpr> literals) implements Command {}
    record GetModel() implements Command {}
    record GetValue(List<Sexpr> terms) implements Command {}
    record GetUnsatCore() implements Command {}
    record ExitCmd() implements Command {}
    /** Unrecognised command: kept verbatim so the command stream round-trips. */
    record Unknown(String head, Sexpr raw) implements Command {}

    /** Commands that consume a solver response, in stream order. */
    default boolean isQuery() {
        return this instanceof CheckSat || this instanceof CheckSatAssuming
            || this instanceof GetModel || this instanceof GetUnsatCore
            || this instanceof GetValue;
    }
}
