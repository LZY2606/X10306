package smtwitness.smt;

import smtwitness.smt.Command.Kind;
import smtwitness.smt.Command.ModelEntry;
import smtwitness.smt.Command.NamedAssert;

import java.util.ArrayList;
import java.util.List;

/** Turns ordered transcript blocks into structured commands while keeping raw text. */
public final class TranscriptParser {

    public record Parsed(List<Command> commands, List<String> diagnostics) {}

    public static Parsed parse(List<Block> blocks) {
        List<String> diag = new ArrayList<>();
        List<Command> out = new ArrayList<>();
        Command.Builder pending = null;
        int checkCounter = 0;

        for (Block b : blocks) {
            if (Block.CMD.equals(b.type())) {
                if (pending != null) out.add(pending.build());
                pending = parseCommand(b, diag);
            } else {
                if (pending == null) {
                    diag.add("block #" + b.seq() + ": response block with no preceding command, kept raw");
                    continue;
                }
                switch (b.type()) {
                    case Block.RESP_MODEL -> attachModel(pending, b, diag);
                    case Block.RESP_CORE -> attachCore(pending, b, diag);
                    default -> attachOther(pending, b, diag);
                }
            }
        }
        if (pending != null) out.add(pending.build());
        return new Parsed(List.copyOf(out), List.copyOf(diag));
    }

    private static Command.Builder parseCommand(Block b, List<String> diag) {
        Command.Builder cb = Command.builder(b.seq(), b.text());
        List<SExpr> forms;
        try {
            forms = SExpr.parseAll(b.text());
        } catch (RuntimeException ex) {
            cb.kind(Kind.OTHER).error("unparseable command: " + ex.getMessage());
            diag.add("block #" + b.seq() + ": " + ex.getMessage());
            return cb;
        }
        cb.rawForms(forms);
        if (forms.isEmpty()) {
            cb.kind(Kind.OTHER).error("empty command block");
            return cb;
        }
        if (forms.size() > 1) diag.add("block #" + b.seq() + ": multiple forms in one command block; only the first is structured");
        if (!(forms.get(0) instanceof SExpr.SList l) || l.items().isEmpty() || !(l.head() instanceof SExpr.Atom ha)) {
            cb.kind(Kind.OTHER).error("command is not a list");
            return cb;
        }
        String head = ha.text();
        Kind kind = Command.kindOf(head).orElse(Kind.OTHER);
        cb.kind(kind);
        try {
            switch (kind) {
                case SET_LOGIC -> cb.logic(atomAt(l, 1));
                case DECLARE_SORT -> { /* unsupported uninterpreted sort */ cb.symbol(atomAt(l, 1)); }
                case DEFINE_SORT -> cb.symbol(atomAt(l, 1));
                case DECLARE_CONST -> { cb.symbol(atomAt(l, 1)); cb.sort(exprAt(l, 2)); }
                case DECLARE_FUN -> parseDeclareFun(cb, l);
                case DEFINE_FUN -> parseDefineFun(cb, l);
                case ASSERT -> parseAssert(cb, l, diag);
                case PUSH -> cb.term(l.items().size() > 1 ? exprAt(l, 1) : new SExpr.Atom("1"));
                case POP -> cb.term(l.items().size() > 1 ? exprAt(l, 1) : new SExpr.Atom("1"));
                case CHECK_SAT_ASSUMING -> parseAssuming(cb, l, diag);
                default -> { }
            }
        } catch (RuntimeException ex) {
            cb.kind(kind).error("malformed " + head + ": " + ex.getMessage());
            diag.add("block #" + b.seq() + ": malformed " + head + ": " + ex.getMessage());
        }
        return cb;
    }

    private static void parseDeclareFun(Command.Builder cb, SExpr.SList l) {
        cb.symbol(atomAt(l, 1));
        // (declare-fun f (args...) result)
        if (l.items().size() >= 4 && l.items().get(3) instanceof SExpr s) cb.sort(s);
        if (l.items().size() >= 3 && l.items().get(2) instanceof SExpr.SList args && !args.items().isEmpty()) {
            // only arity-0 is in the local subset; keep the raw form, engine marks it
        }
    }

    private static void parseDefineFun(Command.Builder cb, SExpr.SList l) {
        // (define-fun name ((arg sort)...) ret body)
        cb.symbol(atomAt(l, 1));
        if (l.items().size() >= 5) {
            ModelEntry entry = modelEntry(l, "block #" + cb.blockSeq(), new ArrayList<>());
            if (entry != null) cb.modelEntries(List.of(entry));
        }
    }

    private static void parseAssert(Command.Builder cb, SExpr.SList l, List<String> diag) {
        if (l.items().size() < 2) throw new IllegalArgumentException("assert needs a term");
        SExpr term = l.items().get(1);
        cb.term(term);
        List<NamedAssert> named = Command.extractNames(l.render());
        cb.namedAsserts(named);
    }

    private static void parseAssuming(Command.Builder cb, SExpr.SList l, List<String> diag) {
        List<String> labels = new ArrayList<>();
        if (l.items().size() >= 2 && l.items().get(1) instanceof SExpr.SList ll) {
            for (SExpr a : ll.items()) {
                if (a instanceof SExpr.Atom at) {
                    String t = at.text();
                    labels.add(t.startsWith("(") ? t : stripBars(t));
                } else if (a instanceof SExpr.SList pl) {
                    // propositional literal form (not p) / p: still remember raw label text
                    labels.add(pl.render());
                }
            }
        }
        cb.assumptions(labels);
    }

    private static void attachModel(Command.Builder cb, Block b, List<String> diag) {
        List<SExpr> forms;
        try {
            forms = SExpr.parseAll(b.text());
        } catch (RuntimeException ex) {
            cb.status("error").error("unparseable model response: " + ex.getMessage());
            diag.add("block #" + b.seq() + ": unparseable model response: " + ex.getMessage());
            return;
        }
        cb.rawForms(appendForms(cb.rawForms(), forms));
        List<ModelEntry> entries = new ArrayList<>();
        for (SExpr f : forms) {
            if (f instanceof SExpr.SList l && "model".equals(l.headSym())) {
                for (SExpr inner : l.items().subList(1, l.items().size())) {
                    if (inner instanceof SExpr.SList il && "define-fun".equals(il.headSym()) && il.items().size() >= 5) {
                        ModelEntry e = modelEntry(il, "model block #" + b.seq(), diag);
                        if (e != null) entries.add(e);
                    } else if (inner instanceof SExpr.SList il && ("declare-fun".equals(il.headSym())
                            || "declare-sort".equals(il.headSym()) || "define-sort".equals(il.headSym())
                            || "forall".equals(il.headSym()))) {
                        // kept raw (uninterpreted/quantified); engine marks unsupported when used
                    }
                }
            } else {
                diag.add("block #" + b.seq() + ": model response does not begin with (model ...), kept raw");
            }
        }
        cb.modelEntries(concat(cb.modelEntries(), entries));
        cb.status(cb.status() == null ? "sat" : cb.status());
    }

    private static ModelEntry modelEntry(SExpr.SList l, String where, List<String> diag) {
        try {
            String name = atomAt(l, 1);
            List<String> argNames = new ArrayList<>();
            List<SExpr> argSorts = new ArrayList<>();
            if (l.items().get(2) instanceof SExpr.SList args) {
                for (SExpr a : args.items()) {
                    if (a instanceof SExpr.SList pair && pair.items().size() >= 2) {
                        argNames.add(pair.items().get(0) instanceof SExpr.Atom na ? stripBars(na.text()) : "?");
                        argSorts.add(pair.items().get(1));
                    }
                }
            }
            SExpr ret = l.items().get(3);
            SExpr body = l.items().get(4);
            return new ModelEntry(name, argNames, argSorts, ret, body);
        } catch (RuntimeException ex) {
            diag.add(where + ": malformed define-fun in response, kept raw");
            return null;
        }
    }

    private static void attachCore(Command.Builder cb, Block b, List<String> diag) {
        List<SExpr> forms;
        try {
            forms = SExpr.parseAll(b.text());
        } catch (RuntimeException ex) {
            cb.status("error").error("unparseable core response: " + ex.getMessage());
            diag.add("block #" + b.seq() + ": " + ex.getMessage());
            return;
        }
        cb.rawForms(appendForms(cb.rawForms(), forms));
        List<String> core = new ArrayList<>();
        for (SExpr f : forms) {
            if (f instanceof SExpr.SList l) {
                for (SExpr a : l.items()) {
                    if (a instanceof SExpr.Atom at) core.add(stripBars(at.text()));
                }
            } else if (f instanceof SExpr.Atom at && isSatWord(at.text().trim())) {
                cb.status(at.text().trim());
            }
        }
        cb.core(concat(cb.core(), core));
        if (cb.status() == null) cb.status("unsat");
    }

    private static void attachOther(Command.Builder cb, Block b, List<String> diag) {
        String t = b.text().trim();
        if ("sat".equals(t) || "unsat".equals(t) || "unknown".equals(t)) {
            cb.status(t);
        } else if (t.startsWith("(error")) {
            cb.error(t);
        } else if ("success".equals(t)) {
            // standard ack, nothing to structure
        } else {
            try {
                cb.rawForms(appendForms(cb.rawForms(), SExpr.parseAll(b.text())));
            } catch (RuntimeException ex) {
                diag.add("block #" + b.seq() + ": unparseable response, kept raw");
            }
        }
    }

    private static List<SExpr> appendForms(List<SExpr> a, List<SExpr> b) {
        List<SExpr> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    private static <T> List<T> concat(List<T> a, List<T> b) {
        List<T> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    private static String atomAt(SExpr.SList l, int i) {
        if (i >= l.items().size() || !(l.items().get(i) instanceof SExpr.Atom a)) {
            throw new IllegalArgumentException("expected atom at position " + i);
        }
        return stripBars(a.text());
    }

    private static SExpr exprAt(SExpr.SList l, int i) {
        if (i >= l.items().size()) throw new IllegalArgumentException("missing position " + i);
        return l.items().get(i);
    }

    private static String stripBars(String s) {
        return s.length() >= 2 && s.startsWith("|") && s.endsWith("|") ? s.substring(1, s.length() - 1) : s;
    }
}
