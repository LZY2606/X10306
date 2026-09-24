package com.witness.smt.check;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evaluation environment: declared constants (with sort), function macros,
 * a value assignment (partial model or a bounded assignment) and optional
 * declared uninterpreted functions (always unsupported to evaluate).
 */
public final class EvalEnv {

    /** Macro from define-fun: params (name -> sort), body. */
    public record Macro(java.util.List<String> params, java.util.List<String> paramSorts,
                        String resultSort, com.witness.smt.sexpr.SExpr body) {}

    private final Map<String, String> sorts = new LinkedHashMap<>();
    private final Map<String, Macro> macros = new LinkedHashMap<>();
    private final Map<String, Value> values = new LinkedHashMap<>();
    private final java.util.Set<String> uninterpretedFuns = new java.util.LinkedHashSet<>();

    public void declare(String sym, String sort) { sorts.put(sym, sort); }
    public String sortOf(String sym) { return sorts.get(sym); }
    public Map<String, String> sorts() { return Map.copyOf(sorts); }

    public void define(String name, Macro m) { macros.put(name, m); }
    public Macro macro(String name) { return macros.get(name); }
    public boolean isMacro(String name) { return macros.containsKey(name); }

    public void assign(String sym, Value v) { values.put(sym, v); }
    public Value value(String sym) { return values.get(sym); }
    public Map<String, Value> values() { return Map.copyOf(values); }

    public void markUninterpretedFun(String name) { uninterpretedFuns.add(name); }
    public boolean isUninterpretedFun(String name) { return uninterpretedFuns.contains(name); }

    public EvalEnv copy() {
        EvalEnv e = new EvalEnv();
        e.sorts.putAll(sorts);
        e.macros.putAll(macros);
        e.values.putAll(values);
        e.uninterpretedFuns.addAll(uninterpretedFuns);
        return e;
    }
}
