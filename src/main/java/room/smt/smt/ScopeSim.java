package room.smt.smt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays the command stream to recover scope structure: push/pop frames,
 * named assertions (with shadowing), declarations, and per-checkpoint snapshots.
 * Rule: an assumption applies to exactly one check (a temporary frame that is
 * popped immediately); after a pop, name bindings of inner frames disappear and
 * outer bindings (including shadowed ones) become visible again.
 */
public final class ScopeSim {

    public static final class Decl {
        public String symbol, sortText, kind; // kind: const|fun|def
        public SExpr body;                    // define-fun body, else null
        public int cmdIndex;
        public int arity;
    }

    public static final class Assertion {
        public String id;        // stable: asrt-<cmdIndex>[-<k>]
        public String name;      // nullable
        public SExpr term;
        public int cmdIndex;
        public int depth;        // frame depth where asserted
        public boolean temporary; // assumption of a check-sat-assuming
    }

    public static final class Frame {
        public int depth;
        public final List<String> assertionIds = new ArrayList<>();
        public final Map<String, String> nameBindings = new LinkedHashMap<>(); // name -> assertion id
        public final Map<String, Decl> decls = new LinkedHashMap<>();
        public boolean temporary;
    }

    public static final class CheckPoint {
        public int cmdIndex;
        public String kind;                 // check-sat | check-sat-assuming
        public List<String> assumptionSymbols = new ArrayList<>(); // raw symbols from command
        public List<Assertion> assumptions = new ArrayList<>();    // resolved temporary assertions
        public List<String> unknownAssumptions = new ArrayList<>();
        public List<String> activeAssertionIds = new ArrayList<>(); // incl. temporary assumptions, in order
        public List<Frame> scopeSnapshot = new ArrayList<>();       // copy of the stack at check time
        public String response;             // first solver response (sat/unsat/unknown/...)
    }

    public static final class CmdInfo {
        public int index;
        public String kind;      // set-logic|declare-const|declare-fun|define-fun|assert|push|pop|reset|reset-assertions|check-sat|check-sat-assuming|get-model|get-unsat-core|get-unsat-assumptions|get-value|other
        public String detail;    // human summary
        public String error;     // nullable
        public SExpr expr;
    }

    public final List<CmdInfo> infos = new ArrayList<>();
    public final List<CheckPoint> checks = new ArrayList<>();
    public final List<Assertion> allAssertions = new ArrayList<>();
    public final Map<String, Assertion> assertionById = new LinkedHashMap<>();
    public final List<String> errors = new ArrayList<>();

    private final Deque<Frame> stack = new ArrayDeque<>();
    private int shadowCounter = 0;

    public ScopeSim() { stack.push(newFrame(0, false)); }

    private static Frame newFrame(int depth, boolean temp) {
        Frame f = new Frame();
        f.depth = depth;
        f.temporary = temp;
        return f;
    }

    public static ScopeSim run(Transcript t) {
        ScopeSim sim = new ScopeSim();
        for (Transcript.Cmd c : t.commands) sim.step(c);
        return sim;
    }

    private void step(Transcript.Cmd c) {
        CmdInfo info = new CmdInfo();
        info.index = c.index;
        infos.add(info);
        SExpr e;
        try {
            e = SExpr.parse(c.text);
        } catch (SExpr.ParseException pe) {
            info.kind = "other";
            info.error = "parse error: " + pe.getMessage();
            errors.add("cmd-" + c.index + ": " + info.error);
            return;
        }
        info.expr = e;
        if (!e.isList() || e.head() == null) { info.kind = "other"; info.detail = e.text; return; }
        String head = e.head();
        switch (head) {
            case "set-logic" -> { info.kind = "set-logic"; info.detail = e.size() > 1 ? e.get(1).text : ""; }
            case "set-info", "set-option", "echo" -> { info.kind = "other"; info.detail = head; }
            case "declare-const" -> {
                info.kind = "declare-const";
                if (e.size() >= 3) { declare(e.get(1).text, e.get(2).text, "const", null, 0, c.index); info.detail = e.get(1).text + " : " + e.get(2).text; }
                else info.error = "malformed declare-const";
            }
            case "declare-fun" -> {
                info.kind = "declare-fun";
                if (e.size() >= 4) {
                    int arity = e.get(2).isList() ? e.get(2).size() : 0;
                    declare(e.get(1).text, e.get(3).text, arity == 0 ? "const" : "fun", null, arity, c.index);
                    info.detail = e.get(1).text + " arity=" + arity + " : " + e.get(3).text;
                } else info.error = "malformed declare-fun";
            }
            case "define-fun" -> {
                info.kind = "define-fun";
                if (e.size() >= 5) {
                    int arity = e.get(2).isList() ? e.get(2).size() : 0;
                    declare(e.get(1).text, e.get(3).text, "def", e.get(4), arity, c.index);
                    info.detail = e.get(1).text + " := " + e.get(4).text;
                } else info.error = "malformed define-fun";
            }
            case "assert" -> {
                info.kind = "assert";
                if (e.size() >= 2) {
                    Assertion a = addAssertion(e.get(1), c.index, false);
                    info.detail = a.id + (a.name != null ? " !" + a.name : "");
                } else info.error = "malformed assert";
            }
            case "push" -> {
                info.kind = "push";
                int n = e.size() > 1 ? parseNat(e.get(1).text, 1) : 1;
                for (int i = 0; i < n; i++) stack.push(newFrame(stack.size(), false));
                info.detail = "depth=" + stack.size();
            }
            case "pop" -> {
                info.kind = "pop";
                int n = e.size() > 1 ? parseNat(e.get(1).text, 1) : 1;
                if (n >= stack.size()) {
                    info.error = "pop underflow: requested " + n + " with depth " + (stack.size() - 1);
                    errors.add("cmd-" + c.index + ": " + info.error);
                    while (stack.size() > 1) stack.pop();
                } else {
                    for (int i = 0; i < n; i++) stack.pop();
                }
                info.detail = "depth=" + stack.size();
            }
            case "reset" -> {
                info.kind = "reset";
                stack.clear();
                stack.push(newFrame(0, false));
                info.detail = "state reset";
            }
            case "reset-assertions" -> {
                info.kind = "reset-assertions";
                for (Frame f : stack) { f.assertionIds.clear(); f.nameBindings.clear(); }
                info.detail = "assertions cleared";
            }
            case "check-sat", "check-sat-assuming" -> {
                boolean assuming = head.equals("check-sat-assuming");
                info.kind = assuming ? "check-sat-assuming" : "check-sat";
                CheckPoint cp = new CheckPoint();
                cp.cmdIndex = c.index;
                cp.kind = info.kind;
                if (assuming && e.size() >= 2 && e.get(1).isList()) {
                    for (SExpr lit : e.get(1).children) {
                        boolean neg = lit.isList() && "not".equals(lit.head()) && lit.size() == 2;
                        String sym = neg ? lit.get(1).text : lit.text;
                        cp.assumptionSymbols.add(lit.text);
                        Assertion bound = resolveName(sym);
                        if (bound != null && !neg) {
                            cp.assumptions.add(bound);
                        } else {
                            Decl d = resolveDecl(sym);
                            if (d != null && "Bool".equals(d.sortText)) {
                                Assertion a = new Assertion();
                                a.id = "assume-" + c.index + "-" + cp.assumptions.size();
                                a.name = null;
                                a.term = neg ? lit : SExpr.atom(sym);
                                a.cmdIndex = c.index;
                                a.depth = stack.size();
                                a.temporary = true;
                                cp.assumptions.add(a);
                            } else {
                                cp.unknownAssumptions.add(lit.text);
                            }
                        }
                    }
                }
                // temporary frame holds the assumptions for exactly this check
                Frame temp = newFrame(stack.size(), true);
                for (Assertion a : cp.assumptions) if (a.temporary) temp.assertionIds.add(a.id);
                stack.push(temp);
                for (Frame f : orderedFrames()) cp.activeAssertionIds.addAll(f.assertionIds);
                cp.scopeSnapshot = snapshot();
                stack.pop(); // assumption applies to exactly one check
                cp.response = c.responses.isEmpty() ? null : c.responses.get(0);
                checks.add(cp);
                info.detail = "active=" + cp.activeAssertionIds.size()
                        + (assuming ? " assuming=" + cp.assumptionSymbols : "");
            }
            case "get-model" -> { info.kind = "get-model"; info.detail = c.responses.isEmpty() ? "(no response)" : "model entries"; }
            case "get-unsat-core" -> { info.kind = "get-unsat-core"; info.detail = c.responses.isEmpty() ? "(no response)" : c.responses.get(0); }
            case "get-unsat-assumptions" -> { info.kind = "get-unsat-assumptions"; info.detail = c.responses.isEmpty() ? "(no response)" : c.responses.get(0); }
            case "get-value" -> { info.kind = "get-value"; info.detail = e.text; }
            case "exit" -> { info.kind = "other"; info.detail = "exit"; }
            default -> { info.kind = "other"; info.detail = head; }
        }
    }

    private static int parseNat(String s, int dflt) {
        try { return Integer.parseInt(s); } catch (Exception e) { return dflt; }
    }

    private Assertion addAssertion(SExpr term, int cmdIndex, boolean temporary) {
        String name = extractName(term);
        Assertion a = new Assertion();
        a.id = "asrt-" + cmdIndex + (shadowCounter++ > 0 ? "-" + shadowCounter : "");
        a.name = name;
        a.term = term;
        a.cmdIndex = cmdIndex;
        a.depth = stack.size() - 1;
        a.temporary = temporary;
        Frame top = stack.peek();
        top.assertionIds.add(a.id);
        if (name != null) top.nameBindings.put(name, a.id); // shadows any same-name binding in this frame
        allAssertions.add(a);
        assertionById.put(a.id, a);
        return a;
    }

    /** Named assertion: (! term :named name) */
    public static String extractName(SExpr term) {
        if (term.isList() && "!".equals(term.head())) {
            for (int i = 1; i + 1 < term.size(); i++) {
                if (term.get(i).isAtom() && ":named".equals(term.get(i).atom))
                    return term.get(i + 1).text;
            }
        }
        return null;
    }

    /** Strip (! term :named n) wrapper to the bare term. */
    public static SExpr bareTerm(SExpr term) {
        if (term.isList() && "!".equals(term.head()) && term.size() >= 2) return term.get(1);
        return term;
    }

    private void declare(String symbol, String sortText, String kind, SExpr body, int arity, int cmdIndex) {
        Decl d = new Decl();
        d.symbol = symbol; d.sortText = sortText; d.kind = kind; d.body = body; d.arity = arity; d.cmdIndex = cmdIndex;
        stack.peek().decls.put(symbol, d);
    }

    public Assertion resolveName(String name) {
        for (Frame f : stack) { // top-down
            String id = f.nameBindings.get(name);
            if (id != null) return assertionById.get(id);
        }
        return null;
    }

    public Decl resolveDecl(String symbol) {
        for (Frame f : stack) {
            Decl d = f.decls.get(symbol);
            if (d != null) return d;
        }
        return null;
    }

    /** All visible declarations, inner frames shadowing outer ones. */
    public Map<String, Decl> visibleDecls() {
        Map<String, Decl> out = new LinkedHashMap<>();
        List<Frame> frames = orderedFrames();
        for (Frame f : frames) out.putAll(f.decls);
        return out;
    }

    private List<Frame> orderedFrames() {
        List<Frame> frames = new ArrayList<>(stack);
        java.util.Collections.reverse(frames); // bottom -> top
        return frames;
    }

    private List<Frame> snapshot() {
        List<Frame> copy = new ArrayList<>();
        for (Frame f : orderedFrames()) {
            Frame g = newFrame(f.depth, f.temporary);
            g.assertionIds.addAll(f.assertionIds);
            g.nameBindings.putAll(f.nameBindings);
            g.decls.putAll(f.decls);
            copy.add(g);
        }
        return copy;
    }
}
