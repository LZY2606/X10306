package smtw.model;

import smtw.smt.Sexpr;
import smtw.smt.SexprParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parsed get-model response: a set of define-fun entries, kept in response order. */
public record SolverModel(List<FuncDef> defs, List<String> diagnostics) {

    public SolverModel { defs = List.copyOf(defs); diagnostics = List.copyOf(diagnostics); }

    public static SolverModel parse(String responseText) {
        List<FuncDef> defs = new ArrayList<>();
        List<String> diag = new ArrayList<>();
        List<Sexpr> top;
        try {
            top = SexprParser.parseAll(responseText);
        } catch (RuntimeException e) {
            return new SolverModel(List.of(), List.of("model parse failure: " + e.getMessage()));
        }
        for (Sexpr s : top) {
            Sexpr.List outer = unwrapModel(s, diag);
            if (outer == null) continue;
            for (Sexpr entry : outer.items()) {
                addEntry(entry, defs, diag);
            }
        }
        return new SolverModel(defs, diag);
    }

    private static Sexpr.List unwrapModel(Sexpr s, List<String> diag) {
        if (!(s instanceof Sexpr.List l)) {
            diag.add("ignored non-list model fragment: " + s.raw());
            return null;
        }
        if (!l.items().isEmpty() && l.items().get(0) instanceof Sexpr.Atom a
                && (a.token().equals("model") || a.token().equals("success"))) {
            return new Sexpr.List(l.items().subList(1, l.items().size()));
        }
        return l;
    }

    private static void addEntry(Sexpr entry, List<FuncDef> defs, List<String> diag) {
        if (!(entry instanceof Sexpr.List l) || l.items().isEmpty()
                || !(l.items().get(0) instanceof Sexpr.Atom head)) {
            diag.add("ignored malformed model entry: " + entry.raw());
            return;
        }
        switch (head.token()) {
            case "define-fun", "define-fun-rec" -> {
                if (l.items().size() != 5) {
                    diag.add("define-fun expects 4 fields, keeping raw: " + entry.raw());
                    return;
                }
                String name = ((Sexpr.Atom) l.items().get(1)).token();
                List<FuncDef.Param> params = new ArrayList<>();
                Sexpr pl = l.items().get(2);
                if (pl instanceof Sexpr.List plist) {
                    for (Sexpr p : plist.items()) {
                        if (p instanceof Sexpr.List pp && pp.items().size() == 2) {
                            params.add(new FuncDef.Param(((Sexpr.Atom) pp.items().get(0)).token(),
                                    ((Sexpr.Atom) pp.items().get(1)).token()));
                        }
                    }
                }
                String res = ((Sexpr.Atom) l.items().get(3)).token();
                defs.add(new FuncDef(name, params, res, l.items().get(4)));
            }
            default -> diag.add("unsupported model entry kind kept as diagnostic: " + head.token());
        }
    }

    public Map<String, FuncDef> byName() {
        Map<String, FuncDef> m = new LinkedHashMap<>();
        for (FuncDef d : defs) m.put(d.name(), d);
        return m;
    }
}
