package witness.smt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes an SMT-LIB command stream, preserving the raw stream, push/pop levels,
 * named assertions (with shadowing), check-sat-assuming queries, declarations and
 * the logic declaration. Assumptions are one-shot: they live only inside the
 * check record that consumed them.
 */
public final class Interpreter {

    public record Decl(String name, List<String> argSorts, String sort, String raw, int level) {}
    public record Define(String name, List<String> params, List<String> paramSorts, String sort, SExp body, int level) {}
    public record AssertRec(String name, SExp term, String raw, int level, int commandIndex) {}
    public record CheckRec(int commandIndex, String kind, List<SExp> assumptions, int level) {}
    public record QueryRec(int commandIndex, String kind) {}

    public static final class Frame {
        public final int level;
        public final Map<String, AssertRec> named = new LinkedHashMap<>();
        public final List<AssertRec> anonymous = new ArrayList<>();
        public final Map<String, Decl> decls = new LinkedHashMap<>();
        public final Map<String, Define> defines = new LinkedHashMap<>();
        Frame(int level) { this.level = level; }
    }

    public static final class State {
        public String logic;
        public final List<String> commandRaws = new ArrayList<>();
        public final List<SExp> commands = new ArrayList<>();
        public final Deque<Frame> frames = new ArrayDeque<>();
        public final List<CheckRec> checks = new ArrayList<>();
        public final List<QueryRec> queries = new ArrayList<>(); // check-sat*, get-model, get-unsat-core in order
        public final List<String> diagnostics = new ArrayList<>();
        { frames.push(new Frame(0)); }

        public int depth() { return frames.size(); }

        /** Active assertions, bottom frame first, command order within a frame. */
        public List<AssertRec> activeAssertions() {
            List<Frame> ordered = new ArrayList<>(frames);
            java.util.Collections.reverse(ordered); // bottom first
            List<AssertRec> out = new ArrayList<>();
            for (Frame f : ordered) {
                out.addAll(f.named.values());
                out.addAll(f.anonymous);
            }
            out.sort(java.util.Comparator.comparingInt(AssertRec::commandIndex));
            return out;
        }

        /** Resolve a name from the top frame downward: inner definitions shadow outer ones. */
        public AssertRec resolve(String name) {
            for (Frame f : frames) {
                AssertRec r = f.named.get(name);
                if (r != null) return r;
            }
            return null;
        }

        public Decl resolveDecl(String name) {
            for (Frame f : frames) {
                Decl d = f.decls.get(name);
                if (d != null) return d;
            }
            return null;
        }

        public Define resolveDefine(String name) {
            for (Frame f : frames) {
                Define d = f.defines.get(name);
                if (d != null) return d;
            }
            return null;
        }

        /** Snapshot of the scope stack for display: outermost frame first. */
        public List<Frame> stackOutermostFirst() {
            List<Frame> ordered = new ArrayList<>(frames);
            java.util.Collections.reverse(ordered);
            return ordered;
        }
    }

    /** Split raw source into per-command raw texts aligned with parseAll output. */
    public static List<String> splitCommands(String src) {
        List<String> raws = new ArrayList<>();
        int depth = 0, start = -1;
        boolean inStr = false, inSym = false, comment = false;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (comment) { if (c == '\n') comment = false; continue; }
            if (inStr) { if (c == '"') inStr = false; continue; }
            if (inSym) { if (c == '|') inSym = false; continue; }
            if (c == ';') { comment = true; continue; }
            if (c == '"') { inStr = true; continue; }
            if (c == '|') { inSym = true; continue; }
            if (c == '(') { if (depth == 0) start = i; depth++; }
            else if (c == ')') { depth--; if (depth == 0 && start >= 0) { raws.add(src.substring(start, i + 1)); start = -1; } }
        }
        return raws;
    }

    public static State run(String src) {
        List<SExp> cmds = Parser.parseAll(src);
        List<String> raws = splitCommands(src);
        State st = new State();
        for (int i = 0; i < cmds.size(); i++) {
            SExp c = cmds.get(i);
            String raw = i < raws.size() ? raws.get(i) : c.toString();
            st.commands.add(c);
            st.commandRaws.add(raw);
            exec(st, c, raw, i);
        }
        return st;
    }

    private static void exec(State st, SExp c, String raw, int idx) {
        if (!c.isList() || c.size() == 0 || !c.get(0).isAtom()) {
            st.diagnostics.add("command " + idx + ": not a command form, preserved raw");
            return;
        }
        String head = c.get(0).atom;
        Frame top = st.frames.peek();
        switch (head) {
            case "set-logic" -> st.logic = c.size() > 1 ? c.get(1).toString() : "";
            case "set-info", "set-option" -> { /* recorded in raw stream only */ }
            case "declare-const" -> {
                if (c.size() >= 3) top.decls.put(c.get(1).atom, new Decl(c.get(1).atom, List.of(), c.get(2).toString(), raw, top.level));
            }
            case "declare-fun" -> {
                if (c.size() >= 4) {
                    List<String> args = new ArrayList<>();
                    if (c.get(2).isList()) for (SExp a : c.get(2).list) args.add(a.toString());
                    top.decls.put(c.get(1).atom, new Decl(c.get(1).atom, args, c.get(3).toString(), raw, top.level));
                }
            }
            case "define-fun" -> {
                if (c.size() >= 5) {
                    List<String> params = new ArrayList<>(), sorts = new ArrayList<>();
                    if (c.get(2).isList()) for (SExp b : c.get(2).list) {
                        if (b.isList() && b.size() >= 2) { params.add(b.get(0).atom); sorts.add(b.get(1).toString()); }
                    }
                    top.defines.put(c.get(1).atom, new Define(c.get(1).atom, params, sorts, c.get(3).toString(), c.get(4), top.level));
                }
            }
            case "assert" -> {
                if (c.size() >= 2) {
                    SExp term = c.get(1);
                    String name = null;
                    if (term.isList() && term.size() >= 4 && term.get(0).isAtom("!")) {
                        for (int k = 2; k + 1 < term.size(); k++) {
                            if (term.get(k).isAtom(":named")) name = term.get(k + 1).atom;
                        }
                        term = term.get(1);
                    }
                    AssertRec rec = new AssertRec(name, term, raw, top.level, idx);
                    if (name != null) top.named.put(name, rec); // same name in this frame replaces; inner frames shadow outer
                    else top.anonymous.add(rec);
                }
            }
            case "push" -> {
                int n = c.size() > 1 ? parseLevel(c.get(1)) : 1;
                for (int k = 0; k < n; k++) st.frames.push(new Frame(st.frames.size()));
            }
            case "pop" -> {
                int n = c.size() > 1 ? parseLevel(c.get(1)) : 1;
                for (int k = 0; k < n; k++) {
                    if (st.frames.size() > 1) st.frames.pop();
                    else st.diagnostics.add("command " + idx + ": pop below base frame ignored");
                }
            }
            case "check-sat" -> {
                CheckRec chk = new CheckRec(idx, "check-sat", List.of(), top.level);
                st.checks.add(chk);
                st.queries.add(new QueryRec(idx, "check"));
            }
            case "check-sat-assuming" -> {
                List<SExp> assumps = new ArrayList<>();
                if (c.size() > 1 && c.get(1).isList()) assumps.addAll(c.get(1).list);
                CheckRec chk = new CheckRec(idx, "check-sat-assuming", List.copyOf(assumps), top.level);
                st.checks.add(chk);
                st.queries.add(new QueryRec(idx, "check"));
            }
            case "get-model" -> st.queries.add(new QueryRec(idx, "model"));
            case "get-unsat-core" -> st.queries.add(new QueryRec(idx, "core"));
            case "get-value", "get-assignment", "get-model-partial?" -> st.queries.add(new QueryRec(idx, "other"));
            case "exit" -> { /* stream marker */ }
            default -> st.diagnostics.add("command " + idx + ": unsupported command '" + head + "', preserved raw");
        }
    }

    private static int parseLevel(SExp e) {
        try { return Math.max(0, Integer.parseInt(e.atom)); } catch (Exception ex) { return 1; }
    }
}
