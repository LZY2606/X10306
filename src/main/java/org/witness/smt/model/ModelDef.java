package org.witness.smt.model;

import org.witness.smt.parse.SExpr;
import org.witness.smt.parse.SmtParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed view of a solver (get-model) response for the local fragment:
 * define-fun / define-const bodies plus unmodelled declare-fun entries
 * (the partial-model case).
 */
public final class ModelDef {
    public record Param(String name, SExpr sort) {}
    public record FunDef(String name, List<Param> params, SExpr resultSort, SExpr body) {
        public boolean isConstant() { return params.isEmpty(); }
    }
    public record Decl(String name, SExpr sort) {}

    public final List<FunDef> funs = new ArrayList<>();
    public final List<Decl> partialDecls = new ArrayList<>();
    public final Map<String, FunDef> byName = new LinkedHashMap<>();
    public final List<String> unsupportedFeatures = new ArrayList<>();
    public final String raw;

    private ModelDef(String raw) { this.raw = raw; }

    public static ModelDef parse(SExpr.List modelExpr, String raw) {
        ModelDef m = new ModelDef(raw);
        for (SExpr e : modelExpr.items().subList(1, modelExpr.items().size())) {
            if (!(e instanceof SExpr.List l) || !(l.head() instanceof SExpr.Atom)) continue;
            switch (l.headName()) {
                case "define-fun" -> {
                    FunDef f = readDef(l);
                    m.funs.add(f);
                    m.byName.put(f.name(), f);
                }
                case "define-const" -> {
                    // (define-const name sort body)  -- solver dialect; treat as 0-ary fun
                    FunDef f = new FunDef(atom(l, 1), List.of(), l.items().get(2), l.items().get(3));
                    m.funs.add(f);
                    m.byName.put(f.name(), f);
                }
                case "declare-fun" -> {
                    String name = atom(l, 1);
                    SExpr.List sig = (SExpr.List) l.items().get(2);
                    SExpr sort = sig.items().isEmpty() ? l.items().get(3)
                            : sortFromSig(sig, l.items().get(3));
                    m.partialDecls.add(new Decl(name, sort));
                }
                case "declare-const" ->
                        m.partialDecls.add(new Decl(atom(l, 1), l.items().get(2)));
                case "forall", "exists", "define-fun-rec" ->
                        m.unsupportedFeatures.add(l.headName());
                default -> m.unsupportedFeatures.add("unknown-model-form:" + l.headName());
            }
        }
        return m;
    }

    private static String atom(SExpr.List l, int i) {
        return ((SExpr.Atom) l.items().get(i)).token();
    }

    private static FunDef readDef(SExpr.List l) {
        String name = atom(l, 1);
        SExpr.List ps = (SExpr.List) l.items().get(2);
        List<Param> params = new ArrayList<>();
        for (SExpr p : ps.items()) {
            SExpr.List pl = (SExpr.List) p;
            params.add(new Param(((SExpr.Atom) pl.items().get(0)).token(), pl.items().get(1)));
        }
        return new FunDef(name, params, l.items().get(3), l.items().get(4));
    }

    private static SExpr sortFromSig(SExpr.List argSorts, SExpr resultSort) {
        List<SExpr> items = new ArrayList<>();
        items.add(SExpr.atom("->"));
        items.addAll(argSorts.items());
        items.add(resultSort);
        return new SExpr.List(items);
    }

    public boolean isPartial(String constName) {
        for (Decl d : partialDecls) if (d.name().equals(constName)) return true;
        return false;
    }
}
