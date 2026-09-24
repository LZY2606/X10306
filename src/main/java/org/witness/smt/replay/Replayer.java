package org.witness.smt.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.witness.smt.SExpr;
import org.witness.smt.domain.Sort;
import org.witness.smt.domain.Units;
import org.witness.smt.replay.Transcript.Entry;

/**
 * Replays a parsed transcript through the scope state machine, preserving the
 * original command stream, push/pop levels, named assertions,
 * check-sat-assuming labels, response order and the declared logic.
 *
 * <p>Stable ids are assigned per entry index ({@code e<n>}) so they survive
 * export and re-import unchanged.
 */
public final class Replayer {

    public static final class Problem {
        public final String entryId;
        public final String kind;
        public final String message;

        public Problem(String entryId, String kind, String message) {
            this.entryId = entryId;
            this.kind = kind;
            this.message = message;
        }
    }

    public final Transcript transcript;
    public final ReplayState state = new ReplayState();
    public final Units units = new Units();
    public final List<Problem> problems = new ArrayList<>();
    public final Map<Integer, String> entryIds = new LinkedHashMap<>();

    private Replayer(Transcript t) {
        this.transcript = t;
    }

    public static Replayer run(Transcript t) {
        Replayer r = new Replayer(t);
        r.replay();
        return r;
    }

    public static String idOf(int entryIndex) {
        return "e" + entryIndex;
    }

    private void replay() {
        for (Entry e : transcript.entries()) {
            entryIds.put(e.index, idOf(e.index));
            switch (e.type) {
                case CMD -> applyCommand(e);
                case STATUS, RESULT -> { /* pairing already validated */ }
                case ANNOT -> applyAnnotation(e);
            }
        }
    }

    private void problem(String entryId, String kind, String message) {
        problems.add(new Problem(entryId, kind, message));
    }

    private void applyCommand(Entry e) {
        String id = idOf(e.index);
        SExpr c = e.command;
        String name = e.commandName;
        Entry response = transcript.entries().get(e.pairedCommand);
        try {
            switch (name) {
                case "set-logic" -> {
                    state.setLogic(c.arg(0).atom());
                    noteLogic(c.arg(0).atom());
                }
                case "set-option", "set-info", "echo" -> { }
                case "declare-const" -> declare(e, c.arg(0).atom(), List.of(), Sort.fromExpr(c.arg(1)));
                case "declare-fun" -> declareFun(e, c);
                case "declare-sort" -> {
                    state.noteUnsupported("uninterpreted sort");
                    problem(id, "unsupported-theory",
                            "declare-sort introduces an uninterpreted sort not checked locally");
                }
                case "define-fun" -> defineFun(e, c);
                case "define-sort" -> { }
                case "assert" -> {
                    state.addAssertion(id, c.arg(0), e.raw);
                    if (response.isError()) {
                        problem(id, "solver-error", errorText(response));
                    }
                }
                case "push" -> state.push();
                case "pop" -> {
                    int levels = c.arity() == 0 ? 1 : Integer.parseInt(c.arg(0).atom());
                    try {
                        state.pop(levels);
                    } catch (IllegalStateException ex) {
                        problem(id, "pop-underflow", ex.getMessage());
                    }
                }
                case "check-sat" -> recordCheck(e, response, List.of());
                case "check-sat-assuming" -> {
                    List<String> labels = new ArrayList<>();
                    for (SExpr s : c.arg(0).items()) {
                        labels.add(s.atom());
                    }
                    for (String label : labels) {
                        if (state.resolveLabel(label) == null) {
                            problem(id, "unknown-assumption",
                                    "temporary assumption '" + label + "' is not an active named label");
                        }
                    }
                    recordCheck(e, response, labels);
                }
                case "get-model", "get-unsat-core", "get-unsat-assumptions" -> attachToCheck(e, response);
                case "reset" -> state.reset();
                case "reset-assertions" -> state.resetAssertions();
                default -> problem(id, "unknown-command", "command '" + name + "' is not handled by the local subset");
            }
        } catch (RuntimeException ex) {
            problem(id, "replay-error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void noteLogic(String logic) {
        if (logic.startsWith("QF_") || "AUFLIA".equals(logic) || "ALIA".equals(logic)
                || "LIA".equals(logic) || "LRA".equals(logic)) {
            return;
        }
        state.noteUnsupported("logic " + logic);
    }

    private void declare(Entry e, String name, List<Sort> args, Sort sort) {
        if (!sort.isSupported()) {
            state.noteUnsupported(((Sort.Unsupported) sort).reason);
        }
        state.declare(name, args, sort, idOf(e.index));
    }

    private void declareFun(Entry e, SExpr c) {
        String name = c.arg(0).atom();
        List<Sort> args = new ArrayList<>();
        for (SExpr a : c.arg(1).items()) {
            Sort s = Sort.fromExpr(a);
            if (!s.isSupported()) {
                state.noteUnsupported(((Sort.Unsupported) s).reason);
            }
            args.add(s);
        }
        Sort result = Sort.fromExpr(c.arg(2));
        if (!result.isSupported()) {
            state.noteUnsupported(((Sort.Unsupported) result).reason);
        }
        state.declare(name, args, result, idOf(e.index));
    }

    private void defineFun(Entry e, SExpr c) {
        String name = c.arg(0).atom();
        List<FunDef.Param> params = new ArrayList<>();
        for (SExpr p : c.arg(1).items()) {
            params.add(new FunDef.Param(p.arg(0).atom(), Sort.fromExpr(p.arg(1))));
        }
        Sort result = Sort.fromExpr(c.arg(2));
        if (!result.isSupported()) {
            state.noteUnsupported(((Sort.Unsupported) result).reason);
        }
        FunDef def = new FunDef(name, params, result, c.arg(3), e.index);
        state.define(def, idOf(e.index));
    }

    private void recordCheck(Entry e, Entry response, List<String> labels) {
        ReplayState.CheckPoint cp = state.newCheck(idOf(e.index), idOf(response.index), labels);
        cp.solverVerdict = switch (response.resultKind) {
            case SAT -> Transcript.ResultKind.SAT;
            case UNSAT -> Transcript.ResultKind.UNSAT;
            default -> Transcript.ResultKind.UNKNOWN;
        };
    }

    private void attachToCheck(Entry e, Entry response) {
        ReplayState.CheckPoint cp = state.lastCheck();
        if (cp == null) {
            problem(idOf(e.index), "orphan-response", "'" + e.commandName + "' without a preceding check");
            return;
        }
        switch (e.commandName) {
            case "get-model" -> {
                cp.modelEntryId = idOf(response.index);
                if (cp.solverVerdict != Transcript.ResultKind.SAT) {
                    problem(idOf(e.index), "unexpected-model",
                            "model supplied for a check whose solver verdict was " + cp.solverVerdict);
                }
            }
            case "get-unsat-core", "get-unsat-assumptions" -> {
                cp.coreEntryId = idOf(response.index);
                if (cp.solverVerdict != Transcript.ResultKind.UNSAT) {
                    problem(idOf(e.index), "unexpected-core",
                            "unsat core supplied for a check whose solver verdict was " + cp.solverVerdict);
                }
            }
            default -> { }
        }
    }

    private void applyAnnotation(Entry e) {
        switch (e.annotKey) {
            case "unit" -> units.putUnit(e.annotSymbol, e.annotValue);
            case "coords", "coordinate-basis" -> units.putCoordinateBasis(e.annotSymbol, e.annotValue);
            default -> problem(idOf(e.index), "unknown-annotation",
                    "annotation key '" + e.annotKey + "' is not understood");
        }
    }

    private static String errorText(Entry response) {
        return response.payload == null ? "solver error" : response.payload.toString();
    }
}
