package org.witness.smt.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.witness.smt.SExpr;
import org.witness.smt.domain.BigRational;
import org.witness.smt.domain.Sort;
import org.witness.smt.domain.Values;
import org.witness.smt.domain.Values.ArrayV;
import org.witness.smt.domain.Values.NumV;
import org.witness.smt.domain.Values.Value;

/**
 * Converts a solver {@code get-model} payload into {@link ModelDef}s.
 *
 * <p>Supported array bodies: nested {@code ite} equality chains,
 * {@code store} chains and {@code (as const ...)} defaults. Anything else
 * (lambda over uninterpreted indices, recursion, unsupported quantifiers)
 * stays attached as an unsupported-theory diagnostic rather than being
 * silently dropped.
 */
public final class ModelParser {

    private final List<String> diagnostics = new ArrayList<>();

    public List<String> diagnostics() {
        return diagnostics;
    }

    public Model parse(SExpr payload, String sourceEntryId) {
        Model model = new Model(sourceEntryId);
        for (SExpr entry : payload.items()) {
            if (!entry.isList() || entry.head() == null) {
                diagnostics.add("model entry is not a define-fun / define-const form: " + entry);
                continue;
            }
            String head = entry.head();
            if ("define-fun".equals(head) && entry.arity() == 4) {
                String name = entry.arg(0).atom();
                SExpr params = entry.arg(1);
                Sort resultSort = Sort.fromExpr(entry.arg(2));
                SExpr body = entry.arg(3);
                model.put(parseEntry(name, params, resultSort, body, sourceEntryId));
            } else if ("define-const".equals(head) && entry.arity() == 3) {
                String name = entry.arg(0).atom();
                Sort sort = Sort.fromExpr(entry.arg(1));
                model.put(parseEntry(name, SExpr.list(List.of(), 1, 1), sort, entry.arg(2), sourceEntryId));
            } else {
                diagnostics.add("unsupported model entry '" + head + "' kept as diagnostic: " + entry);
            }
        }
        return model;
    }

    private ModelDef parseEntry(String name, SExpr params, Sort resultSort, SExpr body, String sourceEntryId) {
        Map<String, Sort> paramMap = new LinkedHashMap<>();
        for (SExpr p : params.items()) {
            if (!p.isList() || p.arity() != 2) {
                diagnostics.add("malformed parameter list for model symbol '" + name + "'");
                continue;
            }
            paramMap.put(p.arg(0).atom(), Sort.fromExpr(p.arg(1)));
        }
        if (paramMap.isEmpty()) {
            Value scalar = evalConst(body, resultSort);
            if (scalar instanceof Values.UnknownV u) {
                diagnostics.add("symbol '" + name + "': " + u.reason());
            }
            return ModelDef.scalar(name, resultSort, scalar, body, sourceEntryId);
        }
        if (resultSort instanceof Sort.ArraySort arraySort && paramMap.size() == 1) {
            return parseArray(name, arraySort, paramMap.keySet().iterator().next(), body, sourceEntryId);
        }
        diagnostics.add("model symbol '" + name + "' uses unsupported function/quantifier form");
        Value u = Values.unknown(resultSort, "unsupported model form");
        return ModelDef.scalar(name, resultSort, u, body, sourceEntryId);
    }

    private ModelDef parseArray(String name, Sort.ArraySort sort, String indexVar, SExpr body, String sourceEntryId) {
        Map<Value, Value> overrides = new LinkedHashMap<>();
        Value defaultCell = collectIte(indexVar, body, overrides);
        if (defaultCell != null) {
            return ModelDef.array(name, sort, new ArrayV(sort, overrides, defaultCell),
                    Map.of(indexVar, sort.index), body, sourceEntryId);
        }
        StoreChain chain = collectStore(body);
        if (chain != null) {
            Value chainDefault = chain.base;
            if (chainDefault == null) {
                chainDefault = Values.unknown(sort.element, "array has no (as const) default");
                diagnostics.add("array '" + name + "' missing an explicit default");
            }
            return ModelDef.array(name, sort, new ArrayV(sort, chain.overrides, chainDefault),
                    Map.of(indexVar, sort.index), body, sourceEntryId);
        }
        diagnostics.add("array '" + name + "' body uses an unsupported form: " + body);
        return ModelDef.array(name, sort,
                new ArrayV(sort, Map.of(), Values.unknown(sort.element, "unsupported array body")),
                Map.of(indexVar, sort.index), body, sourceEntryId);
    }

    /** Pattern: (ite (= i k) v rest). Returns the default value, filling overrides. */
    private Value collectIte(String indexVar, SExpr body, Map<Value, Value> overrides) {
        if (!body.isList() || !"ite".equals(body.head()) || body.arity() != 3) {
            return body == null ? null : evalConst(body, Sort.INT);
        }
        SExpr cond = body.arg(0);
        if (!cond.isList() || !"=".equals(cond.head()) || cond.arity() != 2) {
            return null;
        }
        SExpr lhs = cond.arg(0);
        SExpr rhs = cond.arg(1);
        SExpr keyExpr;
        if (lhs.isAtom() && indexVar.equals(lhs.atom())) {
            keyExpr = rhs;
        } else if (rhs.isAtom() && indexVar.equals(rhs.atom())) {
            keyExpr = lhs;
        } else {
            return null;
        }
        Value key = evalConst(keyExpr, Sort.INT);
        Value cell = evalConst(body.arg(1), null);
        Value rest = collectIte(indexVar, body.arg(2), overrides);
        if (key instanceof Values.UnknownV || cell instanceof Values.UnknownV || rest == null) {
            return null;
        }
        overrides.put(key, cell);
        return rest;
    }

    private record StoreChain(Map<Value, Value> overrides, Value base) {
    }

    private StoreChain collectStore(SExpr body) {
        // ((as const (Array T1 T2)) defaultValue)
        if (body.isList() && body.arity() == 1 && body.arg(0).isList()
                && "as".equals(body.arg(0).head()) && body.arg(0).arity() == 2
                && "const".equals(body.arg(0).arg(0).atom())) {
            Value base = evalConst(body.items().get(1), null);
            return new StoreChain(new LinkedHashMap<>(), base);
        }
        if (body.isList() && "store".equals(body.head()) && body.arity() == 3) {
            StoreChain inner = collectStore(body.arg(0));
            if (inner == null) {
                return null;
            }
            Value key = evalConst(body.arg(1), Sort.INT);
            Value cell = evalConst(body.arg(2), null);
            if (key instanceof Values.UnknownV || cell instanceof Values.UnknownV) {
                return null;
            }
            Map<Value, Value> all = new LinkedHashMap<>(inner.overrides);
            all.put(key, cell);
            return new StoreChain(all, inner.base);
        }
        return null;
    }

    /** Evaluates a closed constant term appearing in a model. */
    public Value evalConst(SExpr e, Sort expected) {
        Sort sort = expected == null ? Sort.REAL : expected;
        if (e == null) {
            return Values.unknown(sort, "missing body");
        }
        if (e.isAtom()) {
            String token = e.atom();
            if ("true".equals(token)) {
                return new Values.BoolV(true);
            }
            if ("false".equals(token)) {
                return new Values.BoolV(false);
            }
            if (isNumber(token)) {
                boolean integral = !token.contains(".") && !token.contains("/");
                return NumV.of(BigRational.parse(token), integral);
            }
            return Values.unknown(sort, "non-constant atom '" + token + "' in model");
        }
        String head = e.head();
        if (head == null) {
            return Values.unknown(sort, "empty list");
        }
        if ("-".equals(head) && e.arity() == 1) {
            Value v = evalConst(e.arg(0), expected);
            return v instanceof NumV n ? NumV.of(n.value().negate(), n.integral()) : v;
        }
        if (e.arity() == 2 && Set.of("+", "-", "*", "/", "div", "mod").contains(head)) {
            Value a = evalConst(e.arg(0), Sort.INT);
            Value b = evalConst(e.arg(1), Sort.INT);
            if (a instanceof NumV x && b instanceof NumV y) {
                boolean integral = x.integral() && y.integral() && !"/".equals(head);
                BigRational r = switch (head) {
                    case "+" -> x.value().add(y.value());
                    case "-" -> x.value().sub(y.value());
                    case "*" -> x.value().mul(y.value());
                    case "/" -> x.value().div(y.value());
                    case "div" -> BigRational.of(intDiv(x.value().num, y.value().num), java.math.BigInteger.ONE);
                    default -> BigRational.of(x.value().num.remainder(y.value().num.abs()),
                            java.math.BigInteger.ONE);
                };
                return NumV.of(r, integral);
            }
            return Values.unknown(Sort.INT, "non-numeric operands in model constant");
        }
        return Values.unknown(sort, "unsupported constant form '" + head + "' in model");
    }

    private static java.math.BigInteger intDiv(java.math.BigInteger a, java.math.BigInteger b) {
        // SMT-LIB div truncates towards zero.
        java.math.BigDecimal ad = new java.math.BigDecimal(a);
        java.math.BigDecimal bd = new java.math.BigDecimal(b);
        java.math.BigDecimal q = ad.divideToIntegralValue(bd);
        if (a.signum() * b.signum() < 0 && a.remainder(b.abs()).signum() != 0) {
            q = q.subtract(java.math.BigDecimal.ONE);
        }
        return q.toBigInteger();
    }

    private static boolean isNumber(String t) {
        try {
            BigRational.parse(t);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
