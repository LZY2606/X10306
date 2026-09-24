package smtvault.derive;

import java.util.LinkedHashMap;
import java.util.Map;
import smtvault.domain.Sexp;

/**
 * Builds local Val representations for array-shaped model bodies:
 *   ((as const (Array Int Bool)) false)
 *   (store (store ((as const (Array Int Bool)) false) 0 true) 1 false)
 * Default values are preserved so array-default evaluation is auditable.
 */
public final class ArrayEval {
    private ArrayEval() {
    }

    public static Val build(Evaluator evaluator, Sexp body) {
        if (body.isCall("as") && body.argc() == 2 && body.arg(0).isCall("const")) {
            EvalTree d = evaluator.eval(body.arg(1), "array-default");
            Val dv = scalar(d);
            return Val.array(dv, new LinkedHashMap<>());
        }
        if (body.isCall("store") && body.argc() == 3) {
            Val array = build(evaluator, body.arg(0));
            Val key = scalar(evaluator.eval(body.arg(1), "array-key"));
            Val val = scalar(evaluator.eval(body.arg(2), "array-val"));
            Map<Val, Val> stores = new LinkedHashMap<>(array.arrayStores);
            stores.put(key, val);
            return Val.array(array.arrayDefault, stores);
        }
        if (body.isCall("const") && body.argc() == 1) {
            return Val.array(scalar(evaluator.eval(body.arg(0), "array-default")),
                    new LinkedHashMap<>());
        }
        return Val.other(body.toString());
    }

    private static Val scalar(EvalTree tree) {
        return switch (tree.value) {
            case null -> Val.other(tree.rawText);
            case "true" -> Val.bool(true);
            case "false" -> Val.bool(false);
            default -> tree.value.matches("-?\\d+")
                    ? Val.integer(Long.parseLong(tree.value), tree.value)
                    : Val.other(tree.value);
        };
    }
}
