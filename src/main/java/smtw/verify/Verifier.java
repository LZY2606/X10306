package smtw.verify;

import smtw.core.CoreVerifier;
import smtw.core.CoreVerification;
import smtw.model.EvalNode;
import smtw.model.Evaluator;
import smtw.model.MeasureAnn;
import smtw.model.SolverModel;
import smtw.sat.SatSolver;
import smtw.sat.SearchParams;
import smtw.sat.SearchResult;
import smtw.scope.Binding;
import smtw.scope.CheckPoint;
import smtw.scope.ScopeMachine;
import smtw.smt.Sexpr;
import smtw.smt.SmtCommand;
import smtw.smt.SymbolTable;
import smtw.smt.Theories;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays the whole session: parses commands/responses, runs the scope machine, performs the
 * local finite search, evaluates every model against every active named assertion (full tree),
 * and verifies any unsat core. All "claim vs local verdict" comparisons are explicit; unknown
 * and unsupported states never collapse into sat/unsat.
 */
public final class Verifier {

    public static final String ALGO_VERSION = "local-replay/v1";

    private final SearchParams params;

    public Verifier(SearchParams params) {
        this.params = params;
    }

    public VerificationReport verify(String transcriptId,
                                     List<SmtCommand> commands,
                                     List<ResponseParser.PairedResponse> responses,
                                     Map<String, MeasureAnn> measures) {
        List<String> global = new ArrayList<>();
        SymbolTable symbols = SymbolTable.build(commands);
        global.addAll(symbols.diagnostics());

        String logicName = commands.stream().filter(c -> c.kind() == smtw.smt.CommandKind.SET_LOGIC)
                .map(c -> Sexpr.token(c.args().get(0))).findFirst().orElse(null);
        Theories.LogicSupport logicSupport = Theories.assessLogic(logicName);
        if (!logicSupport.supported()) global.add("logic '" + logicName + "' unsupported locally: "
                + String.join("; ", logicSupport.reasons()));

        ScopeMachine.Replay scope = new ScopeMachine().replay(commands, transcriptId);
        Map<Integer, ResponseParser.PairedResponse> byOrdinal = new LinkedHashMap<>();
        for (ResponseParser.PairedResponse r : responses) byOrdinal.put(r.requestOrdinal(), r);

        List<CheckVerification> checks = new ArrayList<>();
        for (CheckPoint cp : scope.checkpoints()) {
            checks.add(buildCheck(transcriptId, commands, cp, byOrdinal, symbols,
                    logicSupport, measures));
        }
        for (ResponseParser.PairedResponse r : responses) {
            if (r.requestOrdinal() < 0) global.addAll(r.parseErrors());
        }
        return new VerificationReport(transcriptId, logicSupport, logicName, scope, checks,
                global, ALGO_VERSION, params.fingerprint());
    }

    private CheckVerification buildCheck(String tid, List<SmtCommand> commands, CheckPoint cp,
                                         Map<Integer, ResponseParser.PairedResponse> byOrdinal,
                                         SymbolTable symbols, Theories.LogicSupport logicSupport,
                                         Map<String, MeasureAnn> measures) {
        List<String> diag = new ArrayList<>(cp.diagnostics());
        ResponseParser.PairedResponse checkResp = byOrdinal.get(cp.commandOrdinal());
        String claim;
        if (checkResp == null) {
            claim = "MISSING";
            diag.add("no solver response was captured for this check");
        } else {
            claim = checkResp.raw().isBlank() ? "MISSING" : checkResp.raw().trim();
            diag.addAll(checkResp.parseErrors());
        }
        ResponseParser.PairedResponse modelResp = following(byOrdinal, commands, cp.commandOrdinal(), "model");
        ResponseParser.PairedResponse coreResp = following(byOrdinal, commands, cp.commandOrdinal(), "core");

        // Local search over global active assertions plus one-shot assumptions (this check only).
        List<Sexpr> constraints = new ArrayList<>();
        for (Binding b : cp.activeAssertions()) constraints.add(b.term());
        for (String lit : cp.assumptionLiterals()) {
            String label = lit.startsWith("(not ") && lit.endsWith(")")
                    ? lit.substring(5, lit.length() - 1).trim() : lit;
            boolean neg = !label.equals(lit);
            Binding b = cp.activeAssertions().stream()
                    .filter(x -> x.label().equals(label)).findFirst().orElse(null);
            if (b != null) constraints.add(neg ? Sexpr.list(Sexpr.atom("not"), b.term()) : b.term());
        }
        String localVerdict;
        SearchResult search = null;
        if (!logicSupport.supported()) {
            localVerdict = "UNSUPPORTED";
            diag.add("local search skipped: " + String.join("; ", logicSupport.reasons()));
        } else if (!cp.unresolvedAssumptions().isEmpty()) {
            localVerdict = "ERROR";
        } else {
            search = new SatSolver(symbols, params).search(constraints);
            localVerdict = switch (search.verdict()) {
                case SAT -> "SAT";
                case UNSAT -> "UNSAT";
                case UNKNOWN -> "UNKNOWN";
            };
            diag.addAll(search.reasons());
        }

        List<EvalNode> trees = new ArrayList<>();
        CoreVerification coreVer = null;
        if (modelResp != null && "sat".equals(claim)) {
            SolverModel model = SolverModel.parse(modelResp.raw());
            diag.addAll(model.diagnostics());
            Evaluator ev = new Evaluator(symbols, model, measures);
            int i = 0;
            for (Binding b : cp.activeAssertions()) {
                String treeId = "evaltree:" + tid + ":c" + cp.commandOrdinal() + ":a" + i++;
                EvalNode root = ev.evaluate(treeId, b.term());
                trees.add(root);
                if (root.status() == EvalNode.EvalStatus.UNSUPPORTED) {
                    diag.add("model evaluation for '" + b.label() + "' hit an unsupported theory: "
                            + root.diagnostic());
                }
                if (root.status() == EvalNode.EvalStatus.UNDEF) {
                    diag.add("partial model for '" + b.label() + "': " + root.diagnostic());
                }
            }
        }
        if (coreResp != null) {
            List<String> labels = new ArrayList<>();
            Sexpr e = SexprParserLax.parse(coreResp.raw());
            if (e instanceof Sexpr.List l) {
                for (Sexpr x : l.items()) labels.add(x.raw());
            }
            coreVer = new CoreVerifier(symbols, params)
                    .verify("core:" + tid + ":c" + cp.commandOrdinal(),
                            "check:" + tid + ":c" + cp.commandOrdinal(),
                            labels, cp.activeAssertions(), constraints);
            diag.addAll(coreVer.reasons());
        }

        String agreement = agree(claim, localVerdict, coreVer);
        return new CheckVerification("verify:" + tid + ":c" + cp.commandOrdinal(),
                cp.commandOrdinal(), cp.assuming() ? "check-sat-assuming" : "check-sat",
                cp, claim, localVerdict, agreement, trees, coreVer, cp.assuming(), diag);
    }

    private ResponseParser.PairedResponse following(Map<Integer, ResponseParser.PairedResponse> byOrdinal,
                                                    List<SmtCommand> commands, int checkOrdinal, String kind) {
        for (int i = checkOrdinal + 1; i < commands.size(); i++) {
            ResponseParser.PairedResponse r = byOrdinal.get(i);
            if (r == null) {
                if (kindAt(commands, i)) return null;
                continue;
            }
            if (r.kind().equals(kind)) return r;
            if ("check".equals(r.kind())) return null;
        }
        return null;
    }

    private boolean kindAt(List<SmtCommand> commands, int ordinal) {
        SmtCommand c = commands.get(ordinal);
        return c.kind() == smtw.smt.CommandKind.CHECK_SAT
                || c.kind() == smtw.smt.CommandKind.CHECK_SAT_ASSUMING;
    }

    private String agree(String claim, String local, CoreVerification core) {
        if ("MISSING".equals(claim) || "ERROR".equals(local)) return "NOT-COMPARABLE";
        if ("UNSUPPORTED".equals(local) || "UNKNOWN".equals(local)) return "INCONCLUSIVE";
        if ("sat".equals(claim)) return "SAT".equals(local) ? "AGREE" : "DISAGREE";
        if ("unsat".equals(claim)) {
            if (!"UNSAT".equals(local)) return "DISAGREE";
            if (core != null) {
                return switch (core.verdict()) {
                    case VALID_UNSAT -> "AGREE";
                    case INVALID_SAT -> "DISAGREE";
                    case UNKNOWN -> "INCONCLUSIVE";
                };
            }
            return "AGREE";
        }
        return "INCONCLUSIVE";
    }

    private static final class SexprParserLax {
        static Sexpr parse(String s) {
            try {
                return smtw.smt.SexprParser.parseOne(s);
            } catch (RuntimeException e) {
                return new Sexpr.List(List.of());
            }
        }
    }
}
