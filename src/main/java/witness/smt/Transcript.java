package witness.smt;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parsed SMT-LIB transcript. The original text of every command/response is
 * retained; pairing of check-sat / get-model / get-unsat-core happens here.
 */
public record Transcript(List<Command> commands, List<Query> queries, List<String> formatErrors) {

    public static final Set<String> COMMANDS = Set.of(
            "set-logic", "set-option", "set-info",
            "declare-sort", "define-sort", "declare-datatypes",
            "declare-const", "declare-fun", "define-const", "define-fun", "define-fun-rec", "define-funs-rec",
            "push", "pop", "assert",
            "check-sat", "check-sat-assuming",
            "get-model", "get-unsat-core", "get-unsat-assumptions",
            "get-value", "get-assignment", "get-assertions",
            "echo", "reset", "reset-assertions", "exit");

    public record Query(int checkIdx, String status, Model model, List<Sx> coreExprs,
                        List<Ref> coreRefs, List<String> diagnostics) {
        public String status() { return status; }
    }

    /** A reference inside an unsat core: a named label or an arbitrary propositional term. */
    public record Ref(String name, Sx term, String raw) {}

    public static Transcript parse(String text) {
        List<Fragment> frags = Fragment.scan(text);
        List<Command> cmds = new ArrayList<>();
        List<Query> queries = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Query lastQuery = null;
        Fragment pendingResponse = null;

        for (int fi = 0; fi < frags.size(); fi++) {
            Fragment f = frags.get(fi);
            if (!f.command) { pendingResponse = f; continue; }

            List<Sx> blocks;
            try {
                blocks = Sx.Reader.readAll(f.raw);
            } catch (Sx.SmtException e) {
                errors.add("format error at line " + f.line + ": " + e.getMessage());
                continue;
            }
            if (blocks.isEmpty()) continue;
            for (Sx b : blocks) {
                if (!(b instanceof Sx.List l) || l.items().isEmpty() || !(l.items().get(0) instanceof Sx.Atom k)) {
                    errors.add("format error at line " + lineOf(b) + ": expected a command list");
                    continue;
                }
                Command cmd = Command.parse(cmds.size(), k.text(), l, f.raw, lineOf(b));
                cmds.add(cmd);

                Fragment resp = consumeResponse(frags, fi, blocks.indexOf(b) == blocks.size() - 1);
                // loop helper below instead; kept simple by index lookup
            }
        }
        // Second pass: proper pairing with index control.
        return pair(frags);
    }

    private static Fragment consumeResponse(List<Fragment> frags, int fi, boolean lastBlock) {
        return null;
    }

    private static int lineOf(Sx sx) {
        return switch (sx) {
            case Sx.Atom a -> a.line();
            case Sx.List l -> l.line();
        };
    }

    private static Transcript pair(List<Fragment> frags) {
        List<Command> cmds = new ArrayList<>();
        List<Query> queries = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        Query lastQuery = null;
        int idx = 0, i = 0;
        while (i < frags.size()) {
            Fragment f = frags.get(i);
            if (!f.command) {
                errors.add("format error at line " + f.line + ": solver response without a preceding command");
                i++;
                continue;
            }
            List<Sx> blocks;
            try {
                blocks = Sx.Reader.readAll(f.raw);
            } catch (Sx.SmtException e) {
                errors.add("format error at line " + f.line + ": " + e.getMessage());
                i++;
                continue;
            }
            // collect following response fragments
            List<Fragment> responses = new ArrayList<>();
            int j = i + 1;
            while (j < frags.size() && !frags.get(j).command) { responses.add(frags.get(j)); j++; }

            for (int bi = 0; bi < blocks.size(); bi++) {
                Sx b = blocks.get(bi);
                if (!(b instanceof Sx.List l) || l.items().isEmpty() || !(l.items().get(0) instanceof Sx.Atom k)) {
                    errors.add("format error at line " + f.line + ": expected a command list");
                    continue;
                }
                Command cmd = Command.parse(idx, k.text(), l, f.raw, lineOf(b));
                cmds.add(cmd);
                idx++;

                if (bi == blocks.size() - 1 && !responses.isEmpty()) {
                    Fragment rf = responses.get(0);
                    List<String> diags = new ArrayList<>();
                    switch (cmd.keyword) {
                        case "check-sat", "check-sat-assuming" -> {
                            List<Sx> rblocks = parseBlocks(rf, errors);
                            if (rblocks.isEmpty() || !(rblocks.get(0) instanceof Sx.Atom st)) {
                                errors.add("format error at line " + rf.line + ": check-sat response must be sat/unsat/unknown");
                            } else {
                                String status = st.text();
                                if (!status.equals("sat") && !status.equals("unsat") && !status.equals("unknown")) {
                                    errors.add("format error at line " + rf.line + ": invalid check-sat status '" + status + "'");
                                }
                                List<Sx> extra = new ArrayList<>(rblocks.subList(1, rblocks.size()));
                                for (int ri = 1; ri < responses.size(); ri++) extra.addAll(parseBlocks(responses.get(ri), errors));
                                Model model = null;
                                List<Sx> core = new ArrayList<>();
                                if (!extra.isEmpty()) {
                                    if (status.equals("sat") || status.equals("unknown")) model = Model.parse(extra, rf.line, diags);
                                    else if (status.equals("unsat")) core = extra;
                                }
                                Query q = new Query(cmd.idx, status, model, core, new ArrayList<>(), diags);
                                queries.add(q);
                                lastQuery = q;
                            }
                        }
                        case "get-model" -> {
                            List<Sx> rblocks = parseBlocks(rf, errors);
                            if (lastQuery == null) {
                                errors.add("format error at line " + rf.line + ": get-model response with no preceding check-sat");
                            } else {
                                Model m = Model.parse(rblocks, rf.line, diagsFor(queries, lastQuery));
                                attachModel(queries, lastQuery, m);
                            }
                        }
                        case "get-unsat-core" -> {
                            List<Sx> rblocks = parseBlocks(rf, errors);
                            if (lastQuery == null) {
                                errors.add("format error at line " + rf.line + ": get-unsat-core response with no preceding check-sat");
                            } else {
                                List<Ref> refs = new ArrayList<>();
                                for (Sx rb : rblocks) refs.add(toRef(rb));
                                attachCore(queries, lastQuery, rblocks, refs);
                            }
                        }
                        default -> {
                            List<Sx> rblocks = parseBlocks(rf, errors);
                            for (Sx rb : rblocks) {
                                if (isError(rb)) diags.add("solver reported: " + rb);
                            }
                        }
                    }
                } else if ((cmd.keyword.equals("check-sat") || cmd.keyword.equals("check-sat-assuming")) && responses.isEmpty()) {
                    errors.add("format error at line " + cmd.line + ": " + cmd.keyword + " has no solver response");
                }
            }
            i = j;
        }

        // Resolve core refs after scope replay needs labels; keep raw term too.
        return new Transcript(cmds, queries, errors);
    }

    private static List<String> diagsFor(List<Query> qs, Query target) {
        for (Query q : qs) if (q == target) return q.diagnostics();
        return new ArrayList<>();
    }

    private static void attachModel(List<Query> qs, Query target, Model m) {
        for (int k = 0; k < qs.size(); k++) if (qs.get(k) == target) qs.set(k, new Query(target.checkIdx(), target.status(), m, target.coreExprs(), target.coreRefs(), target.diagnostics()));
    }

    private static void attachCore(List<Query> qs, Query target, List<Sx> exprs, List<Ref> refs) {
        for (int k = 0; k < qs.size(); k++) if (qs.get(k) == target) qs.set(k, new Query(target.checkIdx(), target.status(), target.model(), exprs, refs, target.diagnostics()));
    }

    private static List<Sx> parseBlocks(Fragment f, List<String> errors) {
        try {
            return Sx.Reader.readAll(f.raw);
        } catch (Sx.SmtException e) {
            errors.add("format error at line " + f.line + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static boolean isError(Sx b) {
        return b instanceof Sx.List l && l.items().get(0) instanceof Sx.Atom a && a.text().equals("error");
    }

    private static Ref toRef(Sx b) {
        if (b instanceof Sx.Atom a) return new Ref(a.text(), null, a.text());
        if (b instanceof Sx.List l && l.items().size() == 1 && l.items().get(0) instanceof Sx.Atom a)
            return new Ref(a.text(), null, a.text());
        String raw = Printer.print(b);
        if (b instanceof Sx.List l && l.items().size() == 2
                && l.items().get(0) instanceof Sx.Atom n && n.text().equals("not")
                && l.items().get(1) instanceof Sx.Atom p) {
            return new Ref(null, b, raw);
        }
        return new Ref(null, b, raw);
    }
}
