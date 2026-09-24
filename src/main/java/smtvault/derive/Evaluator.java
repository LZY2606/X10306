package smtvault.derive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import smtvault.domain.Sexp;

/**
 * Evaluator for the deliberately small local semantic subset:
 *   Bool, Int, (Array ...) with defaults/stores, and the usual connectives,
 *   equality, ite, arithmetic (+ - * < <= > >=), select/store.
 * Everything outside the subset (strings, reals arithmetic on reals,
 * quantifiers, bit-vectors, uninterpreted sorts ...) yields UNKNOWN with an
 * explicit "unsupported theory" reason rather than a guessed verdict.
 */
public final class Evaluator {

    private static final Set<String> UNSUPPORTED = Set.of(
            "str.++", "str.len", "str.contains", "str.replace", "str.at", "str.substr",
            "str.prefixof", "str.suffixof", "str.indexof", "str.<", "str.<=",
            "bvadd", "bvmul", "bvnot", "bvand", "bvor", "bvxor", "bvshl", "bvlshr",
            "extract", "concat", "to_real", "to_int", "is_int",
            "sin", "cos", "tan", "sqrt", "real.log", "real.exp", "^",
            "forall", "exists", "lambda", "=>int", "divisible", "distinct");

    private final ModelRepr model;
    private final Map<String, String> sortBasis = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();
    private int nodeCounter = 0;
    private final long seed;
    private final Map<String, Boolean> assumptions = new LinkedHashMap<>();
    private final Map<String, Boolean> envOverride = new LinkedHashMap<>();

    public Evaluator(ModelRepr model, long seed) {
        this.model = model;
        this.seed = seed;
    }

    public void putAssumption(String name, boolean value) {
        assumptions.put(name, value);
    }

    public void putEnv(String name, boolean value) {
        envOverride.put(name, value);
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public void declareSortBasis(String symbol, String basis) {
        sortBasis.put(symbol, basis);
    }

    public EvalTree evaluateAssertion(Sexp term, String nodePrefix) {
        nodeCounter = 0;
        return eval(term, nodePrefix);
    }

    private String nextId(String prefix) {
        return prefix + ".n" + (nodeCounter++);
    }

    private EvalTree unknown(Sexp term, String reason) {
        String id = "ev" + (nodeCounter++) + ":" + term.hashCode();
        EvalTree t = new EvalTree(id, term, term.toString(), null, EvalTree.Status.UNKNOWN, reason,
                basisOf(term));
        diagnostics.add(reason + " :: " + term);
        return t;
    }

    private EvalTree leaf(Sexp term, Val v, String reason) {
        String id = "ev" + (nodeCounter++) + ":" + term.hashCode();
        EvalTree.Status st = v.kind == Val.Kind.BOOL
                ? (v.bool ? EvalTree.Status.SATISFIED : EvalTree.Status.VIOLATED)
                : EvalTree.Status.UNKNOWN;
        return new EvalTree(id, term, term.toString(), v.raw, st, reason, basisOf(term));
    }

    private String basisOf(Sexp term) {
        if (term.isAtom()) {
            String b = sortBasis.get(term.token());
            if (b != null) {
                return b;
            }
        }
        return null;
    }

    public EvalTree eval(Sexp term, String prefix) {
        if (term.isAtom()) {
            return evalAtom(term);
        }
        String op = term.head().isAtom() ? term.head().token() : null;
        if (op != null && UNSUPPORTED.contains(op)) {
            return unknown(term, "unsupported theory construct: " + op);
        }
        if (op == null) {
            return unknown(term, "non-symbol operator cannot be evaluated in local subset");
        }
        return switch (op) {
            case "not", "and", "or", "xor", "=>", "=", "<", "<=", ">", ">=",
                 "+", "-", "*", "div", "mod", "ite", "select", "store", "and()", "or()" ->
                    evalCall(op, term, prefix);
            default -> unknown(term, "unsupported theory construct: " + op);
        };
    }

    private EvalTree evalAtom(Sexp term) {
        String s = term.token();
        if ("true".equals(s)) {
            return leaf(term, Val.bool(true), "literal true");
        }
        if ("false".equals(s)) {
            return leaf(term, Val.bool(false), "literal false");
        }
        if (s.matches("-?\\d+")) {
            return leaf(term, Val.integer(Long.parseLong(s), s), "integer literal");
        }
        if (s.matches("-?\\d+\\.\\d+")) {
            return unknown(term, "real literal outside local integer subset: " + s);
        }
        if (envOverride.containsKey(s)) {
            return leaf(term, Val.bool(envOverride.get(s)), "enumerated Boolean assignment");
        }
        if (assumptions.containsKey(s)) {
            return leaf(term, Val.bool(assumptions.get(s)), "one-shot assumption binding");
        }
        if (model != null && model.defs.containsKey(s)) {
            ModelRepr.FnDef def = model.defs.get(s);
            if (def.body == null) {
                return unknown(term, "partial model: symbol " + s + " declared but not defined");
            }
            if (def.arrayStyle || def.body.isCall("store")) {
                Val arr = ArrayEval.build(this, def.body);
                EvalTree node = new EvalTree("ev" + (nodeCounter++) + ":" + s.hashCode(),
                        term, def.raw, "(array " + arr.arrayDefault + ")",
                        EvalTree.Status.UNKNOWN,
                        "array model with default " + arr.arrayDefault.raw
                                + " and " + arr.arrayStores.size() + " explicit stores",
                        basisOf(term));
                valueCache.put(node.id, arr);
                return node;
            }
            EvalTree body = eval(def.body, "model:" + s);
            return body;
        }
        return unknown(term, "partial model: no value for symbol " + s);
    }

    private EvalTree evalCall(String op, Sexp term, String prefix) {
        List<EvalTree> args = new ArrayList<>();
        for (int i = 0; i < term.argc(); i++) {
            args.add(eval(term.arg(i), prefix));
        }
        String id = nextId(prefix);

        if (args.stream().anyMatch(a -> a.status == EvalTree.Status.UNKNOWN
                || a.status == EvalTree.Status.ERROR)) {
            EvalTree node = new EvalTree(id, term, term.toString(), null, EvalTree.Status.UNKNOWN,
                    "unknown child value; local subset cannot decide this node", basisOf(term));
            node.children.addAll(args);
            return node;
        }

        switch (op) {
            case "not" -> {
                boolean v = !boolOf(args.get(0));
                return boolNode(id, term, v, args, "negation");
            }
            case "and" -> {
                boolean v = true;
                for (EvalTree a : args) {
                    v &= boolOf(a);
                }
                return boolNode(id, term, v, args, "conjunction");
            }
            case "or" -> {
                boolean v = false;
                for (EvalTree a : args) {
                    v |= boolOf(a);
                }
                return boolNode(id, term, v, args, "disjunction");
            }
            case "xor" -> {
                boolean v = boolOf(args.get(0)) ^ boolOf(args.get(1));
                return boolNode(id, term, v, args, "xor");
            }
            case "=>" -> {
                boolean v = !boolOf(args.get(0)) || boolOf(args.get(1));
                return boolNode(id, term, v, args, "implication");
            }
            case "=" -> {
                Val a = valueOf(args.get(0));
                Val b = valueOf(args.get(1));
                boolean v = a.equals(b);
                return boolNode(id, term, v, args, "equality");
            }
            case "<", "<=", ">", ">=" -> {
                long a = valueOf(args.get(0)).integer;
                long b = valueOf(args.get(1)).integer;
                boolean v = switch (op) {
                    case "<" -> a < b;
                    case "<=" -> a <= b;
                    case ">" -> a > b;
                    default -> a >= b;
                };
                return boolNode(id, term, v, args, "integer comparison " + op);
            }
            case "+" -> {
                long v = 0;
                for (EvalTree a : args) {
                    v += valueOf(a).integer;
                }
                return intNode(id, term, v, args);
            }
            case "-" -> {
                if (args.size() == 1) {
                    return intNode(id, term, -valueOf(args.get(0)).integer, args);
                }
                long v = valueOf(args.get(0)).integer;
                for (int i = 1; i < args.size(); i++) {
                    v -= valueOf(args.get(i)).integer;
                }
                return intNode(id, term, v, args);
            }
            case "*" -> {
                long v = 1;
                for (EvalTree a : args) {
                    v *= valueOf(a).integer;
                }
                return intNode(id, term, v, args);
            }
            case "div", "mod" -> {
                long b = valueOf(args.get(1)).integer;
                if (b == 0) {
                    EvalTree node = new EvalTree(id, term, term.toString(), null,
                            EvalTree.Status.ERROR, "division by zero", basisOf(term));
                    node.children.addAll(args);
                    return node;
                }
                long a = valueOf(args.get(0)).integer;
                long v = op.equals("div") ? a / b : Math.floorMod(a, b);
                return intNode(id, term, v, args);
            }
            case "ite" -> {
                boolean cond = boolOf(args.get(0));
                EvalTree chosen = cond ? args.get(1) : args.get(2);
                EvalTree node = new EvalTree(id, term, term.toString(), chosen.value, chosen.status,
                        "ite selected " + (cond ? "then" : "else") + " branch", basisOf(term));
                node.children.addAll(args);
                return node;
            }
            case "select" -> {
                Val array = valueOf(args.get(0));
                Val key = valueOf(args.get(1));
                if (array.kind != Val.Kind.ARRAY) {
                    return unknown(term, "select on non-array value");
                }
                Val hit = array.arrayStores.getOrDefault(key, array.arrayDefault);
                EvalTree node;
                if (array.arrayStores.containsKey(key)) {
                    node = new EvalTree(id, term, term.toString(), hit.raw,
                            hit.kind == Val.Kind.BOOL
                                    ? (hit.bool ? EvalTree.Status.SATISFIED : EvalTree.Status.VIOLATED)
                                    : EvalTree.Status.UNKNOWN,
                            "array store hit", basisOf(term));
                } else {
                    node = new EvalTree(id, term, term.toString(), hit.raw,
                            hit.kind == Val.Kind.BOOL
                                    ? (hit.bool ? EvalTree.Status.SATISFIED : EvalTree.Status.VIOLATED)
                                    : EvalTree.Status.UNKNOWN,
                            "array default value used (partial model gap remains explicit)",
                            basisOf(term));
                }
                node.children.addAll(args);
                return node;
            }
            case "store" -> {
                Val array = valueOf(args.get(0));
                Val key = valueOf(args.get(1));
                Val val = valueOf(args.get(2));
                if (array.kind != Val.Kind.ARRAY) {
                    return unknown(term, "store on non-array value");
                }
                Map<Val, Val> stores = new LinkedHashMap<>(array.arrayStores);
                stores.put(key, val);
                Val updated = Val.array(array.arrayDefault, stores);
                EvalTree node = new EvalTree(id, term, term.toString(), "(store ...)",
                        EvalTree.Status.UNKNOWN, "array value (structural)", basisOf(term));
                node.children.addAll(args);
                valueCache.put(id, updated);
                return node;
            }
            default -> {
                return unknown(term, "unsupported theory construct: " + op);
            }
        }
    }

    private final Map<String, Val> valueCache = new LinkedHashMap<>();

    private EvalTree boolNode(String id, Sexp term, boolean v, List<EvalTree> args, String reason) {
        EvalTree node = new EvalTree(id, term, term.toString(), String.valueOf(v),
                v ? EvalTree.Status.SATISFIED : EvalTree.Status.VIOLATED, reason, basisOf(term));
        node.children.addAll(args);
        return node;
    }

    private EvalTree intNode(String id, Sexp term, long v, List<EvalTree> args) {
        EvalTree node = new EvalTree(id, term, term.toString(), String.valueOf(v),
                EvalTree.Status.UNKNOWN, "integer value (not a proposition)", basisOf(term));
        node.children.addAll(args);
        return node;
    }

    private boolean boolOf(EvalTree tree) {
        return valueOf(tree).bool;
    }

    private Val valueOf(EvalTree tree) {
        Val cached = valueCache.get(tree.id);
        if (cached != null) {
            return cached;
        }
        if (tree.value == null) {
            return Val.other(tree.rawText);
        }
        Val v = switch (tree.value) {
            case "true" -> Val.bool(true);
            case "false" -> Val.bool(false);
            default -> tree.value.matches("-?\\d+")
                    ? Val.integer(Long.parseLong(tree.value), tree.value)
                    : Val.other(tree.value);
        };
        valueCache.put(tree.id, v);
        return v;
    }

    /** Seed accessor kept so deterministic runs are auditable. */
    public long seed() {
        return seed;
    }
}
