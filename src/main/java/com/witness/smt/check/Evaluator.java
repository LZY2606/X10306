package com.witness.smt.check;

import com.witness.smt.sexpr.SExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluator for the finite local semantic subset:
 *   Bool, Int (64-bit checked), arrays with explicit default,
 *   propositional connectives, QF_LIA arithmetic and comparison,
 *   ite / = / distinct / and / or / xor / not / =>,
 *   select/store and (as const (Array ...)) with a default value.
 * Anything outside the subset yields {@link EvalOutcome.Unsupported} (never a guess);
 * a declared symbol absent from a partial model yields {@link EvalOutcome.Unknown}.
 */
public final class Evaluator {

    public EvalOutcome eval(SExpr term, EvalEnv env) {
        if (term instanceof SExpr.Atom a) {
            String t = a.text();
            if ("true".equals(t)) return ok(new Value.BoolV(true), term, "bool literal", List.of());
            if ("false".equals(t)) return ok(new Value.BoolV(false), term, "bool literal", List.of());
            Long n = parseInt(t);
            if (n != null) return ok(new Value.IntV(n), term, "int literal", List.of());
            return evalSymbol(t, term, env);
        }
        SExpr.List l = (SExpr.List) term;
        if (l.items().isEmpty()) {
            return new EvalOutcome.Error("empty application", EvalOutcome.Trace.leaf(term, "error", "()"));
        }
        if (l.head() instanceof SExpr.List cast) {
            if (cast.is("as")) {
                return evalAs(cast, l.args(), term, env);
            }
            if (cast.is("_")) {
                return new EvalOutcome.Unsupported("indexed identifier " + cast.raw(),
                        EvalOutcome.Trace.leaf(term, "unsupported", cast.raw()));
            }
            return new EvalOutcome.Error("bad head " + cast.raw(), EvalOutcome.Trace.leaf(term, "error", cast.raw()));
        }
        String op = ((SExpr.Atom) l.head()).text();
        return switch (op) {
            case "not", "and", "or", "xor", "=>", "=" -> evalBoolOp(op, l.args(), term, env);
            case "distinct" -> evalDistinct(l.args(), term, env);
            case "ite" -> evalIte(l.args(), term, env);
            case "+", "-", "*", "div", "mod" -> evalArith(op, l.args(), term, env);
            case "<", "<=", ">", ">=" -> evalCmp(op, l.args(), term, env);
            case "select" -> evalSelect(l.args(), term, env);
            case "store" -> evalStore(l.args(), term, env);
            case "const" -> new EvalOutcome.Unsupported("array const must be written (as const (Array ...))",
                    EvalOutcome.Trace.leaf(term, "unsupported", op));
            default -> evalApply(op, l.args(), term, env);
        };
    }

    private EvalOutcome evalSymbol(String t, SExpr term, EvalEnv env) {
        if (env.isUninterpretedFun(t)) {
            return new EvalOutcome.Unsupported("uninterpreted function " + t,
                    EvalOutcome.Trace.leaf(term, "unsupported", "uf"));
        }
        Value v = env.value(t);
        if (v != null) {
            return ok(v, term, "symbol " + t, List.of());
        }
        if (env.macro(t) != null) {
            // zero-arg defined const
            EvalOutcome.Macro0 m0 = null;
            var macro = env.macro(t);
            return eval(macro.body(), env);
        }
        if (env.sortOf(t) != null) {
            return new EvalOutcome.Unknown("symbol not present in partial model: " + t,
                    EvalOutcome.Trace.leaf(term, "unknown", "partial-model"));
        }
        return new EvalOutcome.Error("undeclared symbol " + t,
                EvalOutcome.Trace.leaf(term, "error", "undeclared"));
    }

    @SuppressWarnings("unused")
    private record Macro0() {}
