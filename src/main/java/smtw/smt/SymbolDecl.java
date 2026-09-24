package smtw.smt;

import java.util.List;

/** A declared/defined symbol at the point of a transcript command. */
public record SymbolDecl(String name, List<Sort> argSorts, Sort resultSort, int declaredAtCommand, boolean defined) {
    public SymbolDecl { argSorts = List.copyOf(argSorts); }

    public boolean isConst() { return argSorts.isEmpty(); }
}
