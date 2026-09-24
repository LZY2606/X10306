package witness.smtlib;

import witness.sexpr.Sexpr;
import java.util.ArrayList;
import java.util.List;

/**
 * Solver response stream, paired to check-sat commands strictly by order.
 * A response is `sat` / `unsat` / `unknown`, optionally followed by
 * `(model (define-fun ...))` or `(core (name ...))`. Raw text is preserved.
 */
public final class Responses {

    public enum Verdict { SAT, UNSAT, UNKNOWN }

    public record Def(String name, List<String> argNames, Sexpr sort, Sexpr body, String raw) {}
    public record Resp(int index, Verdict verdict, List<Def> model, List<String> core, String raw) {
        public boolean hasModel() { return model != null; }
        public boolean hasCore() { return core != null; }
    }

    public final List<Resp> responses;

    private Responses(List<Resp> responses) { this.responses = responses; }

    public static Responses parse(String src) {
        List<Sexpr> toplevel = Sexpr.parseAll(src);
        List<Resp> out = new ArrayList<>();
        for (Sexpr e : toplevel) {
            if (e.isAtom()) {
                Verdict v = switch (e.atom()) {
                    case "sat" -> Verdict.SAT;
                    case "unsat" -> Verdict.UNSAT;
                    case "unknown" -> Verdict.UNKNOWN;
                    default -> throw new IllegalArgumentException("bad solver response: " + e.atom());
                };
                out.add(new Resp(out.size(), v, null, null, e.atom()));
            } else if (e.head().equals("model")) {
                if (out.isEmpty()) throw new IllegalArgumentException("model without preceding sat");
                Resp prev = out.remove(out.size() - 1);
                List<Def> defs = new ArrayList<>();
                for (int k = 1; k < e.size(); k++) {
                    Sexpr d = e.get(k);
                    if (!d.head().equals("define-fun")) continue;
                    String name = d.get(1).toString();
                    List<String> args = new ArrayList<>();
                    if (d.get(2) instanceof Sexpr.Lst params) {
                        for (Sexpr p : params.items) args.add(p.get(0).toString());
                    }
                    defs.add(new Def(name, args, d.get(3), d.get(4), d.toString()));
                }
                out.add(new Resp(prev.index(), prev.verdict(), defs, prev.core(),
                        prev.raw() + " " + e));
            } else if (e.head().equals("core")) {
                if (out.isEmpty()) throw new IllegalArgumentException("core without preceding unsat");
                Resp prev = out.remove(out.size() - 1);
                List<String> names = new ArrayList<>();
                if (e.size() > 1 && e.get(1) instanceof Sexpr.Lst l) {
                    for (Sexpr n : l.items) names.add(n.toString());
                }
                out.add(new Resp(prev.index(), prev.verdict(), prev.model(), names,
                        prev.raw() + " " + e));
            } else {
                throw new IllegalArgumentException("unexpected response form: " + e.head());
            }
        }
        return new Responses(List.copyOf(out));
    }
}
