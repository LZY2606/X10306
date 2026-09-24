package smtroom.smt;

import smtroom.sexpr.SExpr;
import java.util.ArrayList;
import java.util.List;

/**
 * Solver response transcript. Responses are paired to commands in order of
 * appearance; response order is preserved verbatim.
 */
public record Transcript(List<Response> responses) {

    /** A raw solver response, aligned to the command index that produced it. */
    public record Response(int commandIndex, String raw, SExpr parsed) {
        public boolean isAtom(String s) { return parsed instanceof SExpr.Atom a && a.text().equals(s); }
    }

    /**
     * Response block format (one per line):
     *   #<command-index> <raw response text, may be multi-line via s-expr>
     * Responses may also be given bare, in which case they align in order to
     * the next response-producing command (check-sat*, get-model, get-unsat-core, get-value).
     */
    public static Transcript parse(String src, Script script) {
        List<Response> out = new ArrayList<>();
        List<Integer> responseCmds = new ArrayList<>();
        for (Command c : script.commands()) {
            switch (c.kind()) {
                case CHECK_SAT, CHECK_SAT_ASSUMING, GET_MODEL, GET_UNSAT_CORE, GET_VALUE -> responseCmds.add(c.index());
            }
        }
        int auto = 0;
        List<String> blocks = splitBlocks(src);
        for (String block : blocks) {
            String b = block.strip();
            if (b.isEmpty()) continue;
            int cmdIdx;
            String raw;
            if (b.startsWith("#")) {
                int sp = b.indexOf(' ');
                if (sp < 0) throw new SExpr.SmtParseException("bad response header: " + b);
                cmdIdx = Integer.parseInt(b.substring(1, sp));
                raw = b.substring(sp + 1).strip();
            } else {
                if (auto >= responseCmds.size())
                    throw new SExpr.SmtParseException("more responses than response-producing commands");
                cmdIdx = responseCmds.get(auto++);
                raw = b;
            }
            List<SExpr> parsed = SExpr.parseAll(raw);
            SExpr p = parsed.size() == 1 ? parsed.get(0) : SExpr.list(parsed);
            out.add(new Response(cmdIdx, raw, p));
        }
        return new Transcript(List.copyOf(out));
    }

    /** Blocks separated by lines containing only '---'. */
    static List<String> splitBlocks(String src) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            if (line.strip().equals("---")) { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(line).append('\n');
        }
        out.add(cur.toString());
        return out;
    }

    public Response forCommand(int commandIndex) {
        for (Response r : responses) if (r.commandIndex() == commandIndex) return r;
        return null;
    }
}
