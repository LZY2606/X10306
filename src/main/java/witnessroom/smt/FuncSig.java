package witnessroom.smt;

import java.util.List;

/** Declared uninterpreted/interpreted function signature in the local environment. */
public record FuncSig(String name, List<Sort> argSorts, Sort result, boolean builtin, String source) {
    public FuncSig { argSorts = List.copyOf(argSorts); }
}
