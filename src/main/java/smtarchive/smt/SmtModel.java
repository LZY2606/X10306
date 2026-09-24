package smtarchive.smt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed solver model. Definitions are kept ordered; arrays retain an explicit
 * default term, and symbols absent from the model are reported (partial model).
 */
public record SmtModel(String rawText, List<ModelDef> defs, Map<String, ModelDef> byName) {

    public sealed interface DefBody permits DefBody.Value, DefBody.Array, DefBody.Lambda, DefBody.Other {}
    public record Value(Term term) implements DefBody {}
    /** constant array ((as const (Array I E)) defaultTerm) */
    public record ArrayConst(Term defaultTerm, String sortRaw) implements DefBody {}
    /** (lambda ((i I)) (ite (= i k1) v1 ... default)) with explicit default */
    public record ArrayStore(List<ArrayEntry> entries, Term defaultTerm) implements DefBody {}
    public record Lambda(List<Command.DefineFun.Param> params, Term body) implements DefBody {}
    public record Other(Term term) implements DefBody {}

    public record ArrayEntry(Term index, Term value) {}

    public record ModelDef(String name, Sort sort, DefBody body, String raw, int line, boolean isFunction) {}

    public static SmtModel parse(String rawText, SExpr.List wrapper) {
        List<SExpr.FormatError> ignored = new ArrayList<>();
        List<SExpr> roots = new ArrayList<>();
        if (wrapper != null) roots.addAll(wrapper.items().subList(1, wrapper.items().size()));
        else roots.addAll(SExpr.Parser.parseAll(rawText, ignored));
        List<ModelDef> defs = new ArrayList<>();
        for (SExpr e : roots) {
            if (!(e instanceof SExpr.List dl) || dl.items().isEmpty()) continue;
            String kind = dl.headToken();
            if ("define-fun".equals(kind) || "define-fun-rec".equals(kind) || "define-const".equals(kind)) {
                defs.add(parseDefine(dl));
            } else if ("declare-fun".equals(kind) || "declare-const".equals(kind)) {
                // abstract value: uninterpreted constant without a body
                String nm = dl.items().size() > 1 && dl.items().get(1) instanceof SExpr.Atom na
                        ? SExpr.unquote(na.token()) : "?";
                Sort sort = dl.items().size() > 3 ? Sort.fromSExpr(dl.items().get(dl.items().size() - 1)) : Sort.UNKNOWN;
                defs.add(new ModelDef(nm, sort, new DefBody.Other(new Term.Sym("@abstract")), dl.toString(), dl.line(), false));
            }
        }
        Map<String, ModelDef> byName = new LinkedHashMap<>();
        for (ModelDef d : defs) byName.put(d.name(), d);
        return new SmtModel(rawText, defs, byName);
    }

    private static ModelDef parseDefine(SExpr.List dl) {
        // (define-fun name ((p sort)...) sort body)
        String name = dl.items().size() > 1 && dl.items().get(1) instanceof SExpr.Atom na
                ? SExpr.unquote(na.token()) : "?";
        List<Command.DefineFun.Param> params = new ArrayList<>();
        Sort sort = Sort.UNKNOWN;
        Term body = new Term.Unsupported("empty", "empty model body", "", List.of());
        if (dl.items().size() >= 5 && dl.items().get(2) instanceof SExpr.List pl) {
            for (SExpr p : pl.items()) {
                if (p instanceof SExpr.List pp && pp.items().size() == 2
                        && pp.items().get(0) instanceof SExpr.Atom pa) {
                    params.add(new Command.DefineFun.Param(SExpr.unquote(pa.token()), Sort.fromSExpr(pp.items().get(1))));
                }
            }
            sort = Sort.fromSExpr(dl.items().get(3));
            body = Term.Builder.from(dl.items().get(4));
        } else if (dl.items().size() == 4) { // (define-const name sort body)
            sort = Sort.fromSExpr(dl.items().get(2));
            body = Term.Builder.from(dl.items().get(3));
        }
        DefBody b;
        if (!params.isEmpty()) {
            // SMT-LIB 2.6 models usually wrap function bodies in (lambda ((i I)) body)
            Term effective = unwrapLambda(body);
            if (effective instanceof Term.App app && "const-array".equals(app.op())) {
                b = new DefBody.ArrayConst(app.args().get(0), sort.toString());
            } else if (isArrayIte(effective)) {
                List<ArrayEntry> entries = new ArrayList<>();
                Term def = collectIte(effective, params.get(0).name(), entries);
                b = new DefBody.ArrayStore(entries, def);
            } else {
                b = new DefBody.Lambda(params, effective);
            }
            return new ModelDef(name, sort, b, dl.toString(), dl.line(), true);
        }
        if (body instanceof Term.App app && "const-array".equals(app.op()))
            b = new DefBody.ArrayConst(app.args().get(0), sort.toString());
        else b = new DefBody.Value(body);
        return new ModelDef(name, sort, b, dl.toString(), dl.line(), false);
    }

    private static boolean isArrayIte(Term t) {
        return t instanceof Term.App a && "ite".equals(a.op());
    }

    private static Term unwrapLambda(Term t) {
        if (t instanceof Term.App app && "lambda".equals(app.op()) && app.args().size() == 2
                && app.args().get(1) != null) {
            return app.args().get(1);
        }
        return t;
    }

    private static Term collectIte(Term t, String idxName, List<ArrayEntry> out) {
        if (t instanceof Term.App ite && "ite".equals(ite.op()) && ite.args().size() == 3) {
            Term guard = ite.args().get(0);
            if (guard instanceof Term.App eq && eq.op().equals("=") && eq.args().size() == 2
                    && eq.args().get(0) instanceof Term.Sym s && idxName.equals(s.name())) {
                out.add(new ArrayEntry(eq.args().get(1), ite.args().get(1)));
                return collectIte(ite.args().get(2), idxName, out);
            }
        }
        return t;
    }
}
