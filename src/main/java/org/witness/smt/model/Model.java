package org.witness.smt.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Parsed {@code get-model} payload keyed by symbol name; order preserved. */
public final class Model {

    private final Map<String, ModelDef> defs = new LinkedHashMap<>();
    public final String sourceEntryId;

    public Model(String sourceEntryId) {
        this.sourceEntryId = sourceEntryId;
    }

    public void put(ModelDef def) {
        defs.put(def.name(), def);
    }

    public ModelDef get(String name) {
        return defs.get(name);
    }

    public Map<String, ModelDef> defs() {
        return defs;
    }
}
