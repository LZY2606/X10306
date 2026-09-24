package witnessroom.smt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A solver model. May be partial: declarations missing here evaluate to
 * UNKNOWN with a diagnostic instead of being silently defaulted.
 */
public final class Model {
    public record Entry(String name, String sort, Value value, String raw, boolean supported, String diagnostic) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();

    public Map<String, Entry> entries() { return entries; }
    public List<String> diagnostics() { return diagnostics; }

    public Entry lookup(String name) { return entries.get(name); }

    /** Parse a solver model response: a parenthesized list of define-fun forms. */
    public static Model parse(String src, Map<String, String> declarations) {
        Model m = new Model();
        String trimmed = src.trim();
        List<SExpr> forms = new ArrayList<>();
        if (trimmed.startsWith("(")) {
            SessionParser.ParseOutcome po = SessionParser.parse(trimmed);
            if (!po.ok()) {
                m.diagnostics.addAll(po.diagnostics());
                return m;
            }
            for (Command c : po.commands()) {
                if (c.kind() == Command.Kind.DEFINE_FUN && c.ast() instanceof SExpr.Lst l) {
                    forms.add(l);
                } else if (c.kind() == Command.Kind.UNKNOWN && c.ast() instanceof SExpr.Lst l
                        && !l.items().isEmpty() && l.items().get(0) instanceof SExpr.Atom h
                        && h.value().equals("define-fun")) {
                    forms.add(l);
                } else {
                    // a bare outer list wrapping define-fun forms
                    if (c.ast() instanceof SExpr.Lst l) {
                        boolean allDefine = !l.items().isEmpty();
                        for (SExpr item : l.items()) {
                            if (item instanceof SExpr.Lst inner && !inner.items().isEmpty()
                                    && SExpr.isAtom(inner.items().get(0), "define-fun")) forms.add(inner);
                            else allDefine = false;
                        }
                        if (forms.isEmpty() && !allDefine)
                            m.diagnostics.add("unrecognized model form: " + SExpr.render(c.ast()));
                    }
                }
            }
        } else {
            m.diagnostics.add("model response is not a parenthesized form; kept raw");
            return m;
        }
        for (SExpr form : forms) {
            SExpr.Lst l = (SExpr.Lst) form;
            if (l.items().size() < 5) { m.diagnostics.add("malformed define-fun: " + SExpr.render(l)); continue; }
            String name = SExpr.render(l.items().get(1));
            String sort = SExpr.render(l.items().get(3));
            SExpr body = l.items().get(4);
            String raw = SExpr.render(body);
            boolean argsEmpty = l.items().get(2) instanceof SExpr.Lst al && al.items().isEmpty();
            if (!argsEmpty) {
                m.entries.put(name, new Entry(name, sort,
                        new Value.RawV(raw, sort, "function with arguments is outside the local subset"),
                        raw, false, "function with arguments is outside the local subset"));
                continue;
            }
            if (!SessionParser.supportedSort(sort)) {
                m.entries.put(name, new Entry(name, sort,
                        new Value.RawV(raw, sort, "sort '" + sort + "' unsupported locally; value kept raw"),
                        raw, false, "unsupported theory sort '" + sort + "'"));
                continue;
            }
            Evaluator ev = new Evaluator(new Model());
            EvalNode node = ev.eval(body);
            if (node.status == EvalNode.Status.VALUE && node.value != null) {
                m.entries.put(name, new Entry(name, sort, node.value, raw, true, null));
            } else {
                m.entries.put(name, new Entry(name, sort,
                        new Value.RawV(raw, sort, "could not evaluate model value locally: " + node.reason),
                        raw, false, node.reason));
            }
        }
        // partial-model diagnostics: declared symbols missing from the model
        for (Map.Entry<String, String> d : declarations.entrySet()) {
            if (!m.entries.containsKey(d.getKey()))
                m.diagnostics.add("partial model: declared symbol '" + d.getKey() + "' (" + d.getValue() + ") has no definition");
        }
        return m;
    }
}
