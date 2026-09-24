package smtvault.derive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import smtvault.domain.CmdRecord;
import smtvault.domain.Sexp;
import smtvault.domain.Transcript;

/**
 * Partial model extracted from a (model ...) response. Each definition keeps
 * its original SMT-LIB representation. Arrays remember their default value
 * and explicit stores. Undeclared/undefined symbols are "partial" gaps rather
 * than guesses.
 */
public final class ModelRepr {

    public static final class FnDef {
        public final String name;
        public final Sexp sort;
        public final Sexp body;
        public final String raw;
        public final boolean arrayStyle;
        public final Sexp arrayExpr;

        FnDef(String name, Sexp sort, Sexp body, String raw, boolean arrayStyle, Sexp arrayExpr) {
            this.name = name;
            this.sort = sort;
            this.body = body;
            this.raw = raw;
            this.arrayStyle = arrayStyle;
            this.arrayExpr = arrayExpr;
        }
    }

    public final Map<String, FnDef> defs = new LinkedHashMap<>();
    public final List<String> diagnostics = new ArrayList<>();
    public CmdRecord source;
    public int checkCmdSeq = -1;

    public static ModelRepr extractAfter(Transcript t, int checkSeq) {
        ModelRepr m = new ModelRepr();
        m.checkCmdSeq = checkSeq;
        for (CmdRecord cmd : t.commands) {
            if (cmd.seq <= checkSeq) {
                continue;
            }
            if ("get-model".equals(cmd.commandName()) || "check-sat".equals(cmd.commandName())
                    || "check-sat-assuming".equals(cmd.commandName())) {
                if (m.source != null || !"get-model".equals(cmd.commandName())) {
                    break;
                }
            }
            Sexp r = cmd.response;
            if (r != null && r.isCall("model")) {
                m.source = cmd;
                parseModel(r, m);
                break;
            }
        }
        return m;
    }

    private static void parseModel(Sexp model, ModelRepr m) {
        for (Sexp def : model.children()) {
            if (def.isCall("declare-fun") && def.argc() == 3) {
                m.defs.put(def.arg(0).token(), new FnDef(def.arg(0).token(), def.arg(2),
                        null, def.toString(), false, null));
            } else if (def.isCall("define-fun") && def.argc() >= 4) {
                Sexp body = def.arg(3);
                boolean arrayStyle = body.isCall("as") && body.argc() == 2
                        && body.arg(0).isCall("const");
                m.defs.put(def.arg(0).token(),
                        new FnDef(def.arg(0).token(), def.arg(2), body, def.toString(),
                                arrayStyle, body));
            } else if (def.isCall("define-fun-rec") || def.isCall("define-funs-rec")) {
                m.diagnostics.add("recursive model definition kept raw, evaluation unsupported: "
                        + def.toString());
            } else if (def.isCall("forall") || def.isCall("exists")) {
                m.diagnostics.add("quantifier in model kept raw: " + def.toString());
            } else {
                m.diagnostics.add("unrecognized model entry kept raw: " + def.toString());
            }
        }
    }
}
