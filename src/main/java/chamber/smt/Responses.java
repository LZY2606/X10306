package chamber.smt;

import java.util.ArrayList;
import java.util.List;

/** Solver response stream, kept in original order and paired 1:1 with response-producing commands. */
public sealed interface Response {

    SExpr raw();

    record Sat(SExpr raw) implements Response {}
    record Unsat(SExpr raw) implements Response {}
    record Unknown(SExpr raw) implements Response {}
    /** Model as list of define-fun s-expressions (partial models allowed: some symbols may be absent). */
    record Model(List<SExpr> definitions, SExpr raw) implements Response {}
    /** Unsat core as a list of names; never assumed minimal. */
    record Core(List<String> names, SExpr raw) implements Response {}
    record Error(String message, SExpr raw) implements Response {}

    static List<Response> parseAll(String text) {
        List<SExpr> exprs = SExpr.parseAll(text);
        List<Response> out = new ArrayList<>();
        for (SExpr e : exprs) out.add(parse(e));
        return List.copyOf(out);
    }

    static Response parse(SExpr e) {
        String a = SExpr.atom(e);
        if (a != null) {
            return switch (a) {
                case "sat" -> new Sat(e);
                case "unsat" -> new Unsat(e);
                case "unknown" -> new Unknown(e);
                default -> throw new SExpr.ParseException("unrecognized solver response: " + a, e.line(), e.col());
            };
        }
        List<SExpr> items = SExpr.list(e);
        if (items.isEmpty()) return new Core(List.of(), e);
        String head = SExpr.atom(items.get(0));
        if ("error".equals(head)) {
            return new Error(items.size() > 1 ? items.get(1).toString() : "error", e);
        }
        if ("model".equals(head)) {
            return new Model(List.copyOf(items.subList(1, items.size())), e);
        }
        boolean allAtoms = items.stream().allMatch(i -> SExpr.atom(i) != null);
        if (allAtoms) {
            return new Core(items.stream().map(i -> SExpr.atom(i)).toList(), e);
        }
        // bare list of define-fun without (model ...) wrapper
        boolean allDefs = items.stream().allMatch(i -> {
            List<SExpr> l = SExpr.list(i);
            return l != null && !l.isEmpty() && "define-fun".equals(SExpr.atom(l.get(0)));
        });
        if (allDefs) return new Model(List.copyOf(items), e);
        throw new SExpr.ParseException("unrecognized solver response form", e.line(), e.col());
    }
}
