package smtvault.derive;

import java.util.Map;
import smtvault.domain.Sexp;

/** Evaluates Boolean-propositional terms against a concrete assignment. */
public final class Propositions {
    private Propositions() {
    }

    public static EvalTree evaluate(Sexp term, Map<String, Boolean> env, long seed) {
        Evaluator emptyModel = new Evaluator(null, seed);
        for (Map.Entry<String, Boolean> e : env.entrySet()) {
            emptyModel.putEnv(e.getKey(), e.getValue());
        }
        return emptyModel.evaluateAssertion(term, "prop");
    }
}
