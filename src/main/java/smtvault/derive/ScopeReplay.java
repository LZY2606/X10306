package smtvault.derive;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import smtvault.domain.CmdRecord;
import smtvault.domain.Sexp;
import smtvault.domain.Transcript;

/**
 * Replays a transcript through an SMT-LIB push/pop stack.
 *
 * Semantic subset enforced explicitly:
 *  - push n / pop n create and destroy nested scope frames;
 *  - declare / define / assert go to the top frame;
 *  - (! ... :named N) names an assertion; a later same-named assertion in a
 *    nested frame shadows the outer one until the frame is popped;
 *  - check-sat-assuming assumptions apply only to that one check;
 *  - popped material is retained as history with its scope depth so old states
 *    stay reviewable.
 */
public final class ScopeReplay {

    public static final class Assertion {
        public final String name;
        public final Sexp term;
        public final int cmdSeq;
        public final int depth;
        public final String raw;
        public boolean shadowed;

        Assertion(String name, Sexp term, int cmdSeq, int depth, String raw) {
            this.name = name;
            this.term = term;
            this.cmdSeq = cmdSeq;
            this.depth = depth;
            this.raw = raw;
        }
    }

    public static final class Frame {
        public final int depth;
        public final int cmdSeq;
        public final List<Assertion> assertions = new ArrayList<>();
        public final Map<String, Sexp> declarations = new LinkedHashMap<>();

        Frame(int depth, int cmdSeq) {
            this.depth = depth;
            this.cmdSeq = cmdSeq;
        }
    }

    public static final class CheckPoint {
        public final int cmdSeq;
        public final String kind; // check-sat | check-sat-assuming
        public final int depth;
        public final List<String> assumptions;
        public final List<Assertion> activeAssertions;
        public final List<Assertion> shadowedAssertions;
        public final String solverSaid;

        CheckPoint(int cmdSeq, String kind, int depth, List<String> assumptions,
                   List<Assertion> active, List<Assertion> shadowed, String solverSaid) {
            this.cmdSeq = cmdSeq;
            this.kind = kind;
            this.depth = depth;
            this.assumptions = assumptions;
            this.activeAssertions = active;
            this.shadowedAssertions = shadowed;
            this.solverSaid = solverSaid;
        }
    }

    public static final class Snapshot {
        public final int afterCmdSeq;
        public final Deque<Frame> stack;

        Snapshot(int afterCmdSeq, Deque<Frame> stack) {
            this.afterCmdSeq = afterCmdSeq;
            this.stack = stack;
        }
    }

    public final List<String> diagnostics = new ArrayList<>();
    public final List<CheckPoint> checks = new ArrayList<>();
    public final List<Snapshot> timeline = new ArrayList<>();

    private final Deque<Frame> stack = new ArrayDeque<>();

    public ScopeReplay() {
        stack.push(new Frame(0, -1));
        timeline.add(new Snapshot(-1, cloneStack()));
    }

    public void run(Transcript t) {
        for (CmdRecord cmd : t.commands) {
            apply(cmd);
            timeline.add(new Snapshot(cmd.seq, cloneStack()));
        }
    }

    public Deque<Frame> liveStack() {
        return stack;
    }

    private Deque<Frame> cloneStack() {
        Deque<Frame> copy = new ArrayDeque<>();
        List<Frame> all = new ArrayList<>(stack);
        java.util.Collections.reverse(all);
        for (Frame f : all) {
            Frame c = new Frame(f.depth, f.cmdSeq);
            c.declarations.putAll(f.declarations);
            c.assertions.addAll(f.assertions);
            copy.push(c);
        }
        return copy;
    }

    public int depth() {
        return stack.size() - 1;
    }

    public void apply(CmdRecord cmd) {
        if (cmd.malformed()) {
            diagnostics.add("cmd " + cmd.seq + ": malformed command ignored by replay: " + cmd.parseError);
            return;
        }
        Sexp c = cmd.command;
        if (!c.isList() || c.head().isAtom() == false) {
            return;
        }
        String name = c.head().token();
        switch (name) {
            case "push" -> {
                int n = c.argc() >= 1 ? parseNum(c.arg(0)) : 1;
                for (int i = 0; i < n; i++) {
                    stack.push(new Frame(depth() + 1, cmd.seq));
                }
            }
            case "pop" -> {
                int n = c.argc() >= 1 ? parseNum(c.arg(0)) : 1;
                for (int i = 0; i < n; i++) {
                    if (stack.size() <= 1) {
                        diagnostics.add("cmd " + cmd.seq + ": pop at global scope ignored");
                        break;
                    }
                    stack.pop();
                }
                refreshShadow();
            }
            case "declare-const", "declare-fun" -> {
                if (c.argc() >= 1) {
                    stack.peek().declarations.put(c.arg(0).token(), c.argc() >= 2 ? c.arg(c.argc() - 1) : null);
                }
            }
            case "define-fun" -> {
                if (c.argc() >= 1) {
                    stack.peek().declarations.put(c.arg(0).token(), c);
                }
            }
            case "assert" -> addAssertion(cmd);
            case "check-sat" -> recordCheck(cmd, "check-sat", List.of());
            case "check-sat-assuming" -> recordAssumingCheck(cmd);
            default -> {
                // get-model / get-unsat-core etc. are responses handled elsewhere
            }
        }
    }

    private void addAssertion(CmdRecord cmd) {
        Sexp term = cmd.command.arg(0);
        String assertedName = null;
        if (term.isCall("!") && term.argc() >= 2) {
            for (int i = 2; i < term.argc(); i++) {
                Sexp opt = term.arg(i);
                if (opt.isList() && opt.argc() == 2 && opt.arg(0).isAtom()
                        && opt.arg(0).token.equals(":named")) {
                    assertedName = opt.arg(1).token;
                }
            }
            term = term.arg(1);
        }
        Assertion assertion = new Assertion(assertedName, term, cmd.seq, depth(), term.toString());
        stack.peek().assertions.add(assertion);
        refreshShadow();
    }

    private void refreshShadow() {
        Map<String, Assertion> topByName = new LinkedHashMap<>();
        for (Frame f : framesInnerToOuter()) {
            for (Assertion a : f.assertions) {
                if (a.name != null) {
                    a.shadowed = topByName.containsKey(a.name);
                    topByName.putIfAbsent(a.name, a);
                }
            }
        }
    }

    private List<Frame> framesInnerToOuter() {
        List<Frame> frames = new ArrayList<>(stack);
        return frames; // ArrayDeque iterator is inner-first
    }

    private List<Assertion> activeAssertions() {
        Map<String, Assertion> byName = new LinkedHashMap<>();
        List<Assertion> ordered = new ArrayList<>();
        for (Frame f : framesOuterToInner()) {
            for (Assertion a : f.assertions) {
                if (a.name != null && byName.containsKey(a.name)) {
                    continue;
                }
                if (a.name != null) {
                    byName.put(a.name, a);
                }
                ordered.add(a);
            }
        }
        return ordered;
    }

    private List<Assertion> shadowedAssertions() {
        List<Assertion> shadowed = new ArrayList<>();
        Map<String, Assertion> topByName = new LinkedHashMap<>();
        for (Frame f : framesInnerToOuter()) {
            for (Assertion a : f.assertions) {
                if (a.name != null) {
                    if (!topByName.containsKey(a.name)) {
                        topByName.put(a.name, a);
                    } else {
                        shadowed.add(a);
                    }
                }
            }
        }
        return shadowed;
    }

    private List<Frame> framesOuterToInner() {
        List<Frame> frames = new ArrayList<>(stack);
        java.util.Collections.reverse(frames);
        return frames;
    }

    private void recordAssumingCheck(CmdRecord cmd) {
        List<String> assumptions = new ArrayList<>();
        if (cmd.command.argc() >= 1 && cmd.command.arg(0).isList()) {
            for (Sexp sym : cmd.command.arg(0).children()) {
                assumptions.add(sym.token);
            }
        }
        recordCheck(cmd, "check-sat-assuming", assumptions);
    }

    private void recordCheck(CmdRecord cmd, String kind, List<String> assumptions) {
        String said = cmd.response != null ? cmd.response.token() : null;
        CheckPoint cp = new CheckPoint(cmd.seq, kind, depth(), assumptions,
                activeAssertions(), shadowedAssertions(), said);
        checks.add(cp);
    }

    private static int parseNum(Sexp s) {
        try {
            return Integer.parseInt(s.token());
        } catch (RuntimeException e) {
            return 1;
        }
    }
}
