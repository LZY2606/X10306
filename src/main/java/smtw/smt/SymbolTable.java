package smtw.smt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collects declare-const/declare-fun/define-fun symbols in transcript order. */
public final class SymbolTable {

    private final Map<String, SymbolDecl> symbols = new LinkedHashMap<>();
    private final Map<String, String> declaredSorts = new LinkedHashMap<>();
    private final List<String> diagnostics = new ArrayList<>();

    public static SymbolTable build(List<SmtCommand> commands) {
        SymbolTable t = new SymbolTable();
        for (SmtCommand c : commands) t.apply(c);
        return t;
    }

    private void apply(SmtCommand cmd) {
        switch (cmd.kind()) {
            case DECLARE_SORT -> {
                if (cmd.args().size() == 2) {
                    declaredSorts.put(Sexpr.token(cmd.args().get(0)), Sexpr.token(cmd.args().get(1)));
                }
            }
            case DECLARE_CONST -> {
                String name = Sexpr.token(cmd.args().get(0));
                Sort sort = Sort.parse(cmd.args().get(1));
                put(new SymbolDecl(name, List.of(), sort, cmd.ordinal(), false), cmd);
            }
            case DECLARE_FUN -> {
                String name = Sexpr.token(cmd.args().get(0));
                List<Sort> args = new ArrayList<>();
                if (cmd.args().get(1) instanceof Sexpr.List pl) {
                    for (Sexpr s : pl.items()) args.add(Sort.parse(s));
                }
                Sort res = Sort.parse(cmd.args().get(2));
                put(new SymbolDecl(name, args, res, cmd.ordinal(), false), cmd);
            }
            case DEFINE_FUN -> {
                String name = Sexpr.token(cmd.args().get(0));
                List<Sort> args = new ArrayList<>();
                if (cmd.args().get(1) instanceof Sexpr.List pl) {
                    for (Sexpr s : pl.items()) {
                        if (s instanceof Sexpr.List pp && pp.items().size() == 2) {
                            args.add(Sort.parse(pp.items().get(1)));
                        }
                    }
                }
                Sort res = Sort.parse(cmd.args().get(2));
                put(new SymbolDecl(name, args, res, cmd.ordinal(), true), cmd);
            }
            default -> { }
        }
    }

    private void put(SymbolDecl d, SmtCommand cmd) {
        if (symbols.put(d.name(), d) != null) {
            diagnostics.add("symbol '" + d.name() + "' re-declared at command " + cmd.ordinal()
                    + " (kept the latest binding; transcript raw text is unchanged)");
        }
    }

    public Map<String, SymbolDecl> symbols() { return symbols; }
    public Map<String, String> declaredSorts() { return declaredSorts; }
    public List<String> diagnostics() { return diagnostics; }
}
