package org.witness.smt.replay;

import java.util.List;
import org.witness.smt.SExpr;
import org.witness.smt.domain.Sort;

/** A {@code define-fun} definition captured during replay. */
public record FunDef(String name, List<Param> params, Sort resultSort, SExpr body, int entryIndex) {

    public record Param(String name, Sort sort) {
    }

    public boolean isConstant() {
        return params.isEmpty();
    }
}
