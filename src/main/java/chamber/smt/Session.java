package chamber.smt;

import java.util.ArrayList;
import java.util.List;

/** A parsed SMT-LIB session: full command stream, raw text retained verbatim. */
public record Session(String rawText, List<Command> commands, String logic) {

    public static Session parse(String rawText) {
        List<SExpr> exprs = SExpr.parseAll(rawText);
        List<Command> cmds = new ArrayList<>();
        String logic = null;
        for (SExpr e : exprs) {
            Command c = Command.parse(e);
            if (c instanceof Command.SetLogic sl) logic = sl.logic();
            cmds.add(c);
        }
        return new Session(rawText, List.copyOf(cmds), logic);
    }
}
