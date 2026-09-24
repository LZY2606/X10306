package org.witness.smt.model;

import java.util.LinkedHashMap;
import java.util.Map;
import org.witness.smt.SExpr;
import org.witness.smt.domain.Sort;
import org.witness.smt.domain.Values.ArrayV;
import org.witness.smt.domain.Values.Value;

/**
 * One {@code define-fun} entry from a solver model, classified as either a
 * scalar value or a finite array with a default value and overrides.
 * Partial models simply omit symbols; absence is handled by the evaluator.
 */
public record ModelDef(String name, Sort resultSort, boolean isArray, Value scalar,
                       ArrayV array, Map<String, Sort> params, SExpr rawBody, String sourceEntryId) {

    public static ModelDef scalar(String name, Sort sort, Value value, SExpr rawBody, String sourceEntryId) {
        return new ModelDef(name, sort, false, value, null, Map.of(), rawBody, sourceEntryId);
    }

    public static ModelDef array(String name, Sort.ArraySort sort, ArrayV value,
                                 Map<String, Sort> params, SExpr rawBody, String sourceEntryId) {
        return new ModelDef(name, sort, true, null, value, new LinkedHashMap<>(params), rawBody, sourceEntryId);
    }
}
