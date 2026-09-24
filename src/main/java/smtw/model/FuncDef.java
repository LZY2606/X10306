package smtw.model;

import smtw.smt.Sexpr;

import java.util.List;

/**
 * A (define-fun name ((arg sort)...) resultSort body) entry from a solver model.
 * Zero-argument define-fun entries model constants.
 */
public record FuncDef(String name, List<Param> params, String resultSort, Sexpr body) {

    public record Param(String sym, String sort) {}

    public boolean isConstant() { return params.isEmpty(); }
}
