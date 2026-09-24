package org.witness.smt.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.witness.smt.SExpr;
import org.witness.smt.core.CoreReduction;
import org.witness.smt.core.CoreReduction.CoreCheck;
import org.witness.smt.core.CoreReduction.ModelCheck;
import org.witness.smt.domain.Sort;
import org.witness.smt.domain.Values;
import org.witness.smt.domain.Values.Value;
import org.witness.smt.model.EvalTree;
import org.witness.smt.model.Model;
import org.witness.smt.model.ModelDef;
import org.witness.smt.model.ModelParser;
import org.witness.smt.replay.Replayer;
import org.witness.smt.replay.ReplayState;
import org.witness.smt.replay.Transcript;
import org.witness.smt.semantics.BoundedSolver;

/**
 * Pure analysis for one transcript revision: replay it, then derive a model
 * check and a core check for every check-sat in the stream. No persistence
 * happens here, so the same analysis is reusable for the counterexample
 * archive and tests.
 */
public final class RevisionAnalysis {

    public enum RevisionKind { INITIAL, PARAM_REVISION, SOURCE_CHANGE }

    public record ScopeFrame(int depth, List<String> assertionIds) {
    }

    public record AssertionView(String id, String raw, int depth, Map<String, String> labels,
                                boolean active) {
    }

    public record CheckView(int sequence, String commandEntryId, String responseEntryId,
                            String solverVerdict, int depth, List<String> assumptions,
                            List<String> activeAssertionIds,
                            String modelDerivedId, String coreDerivedId) {
    }

    public record ModelSummary(String derivedId, String status, List<AssertionEvalView> evals,
                               List<ModelValueView> modelValues,
                               List<String> unsupported, List<String> missingSymbols,
                               List<String> diagnostics) {
    }

    public record AssertionEvalView(String assertionId, String raw, String value, EvalTree tree) {
    }

    public record ModelValueView(String symbol, String sort, String value, String sourceEntryId,
                                 boolean partial, boolean unsupported, String rawBody) {
    }

    public record CoreSummary(String derivedId, String status, boolean unsat, boolean sourcesPreserved,
                              boolean minimal, List<String> labels, List<String> unknownLabels,
                              List<AttemptView> attempts, List<String> diagnostics) {
    }

    public record AttemptView(String stableId, String label, String sourceAssertionId,
                              String status, String result, String reason) {
    }

    public record Analysis(Transcript transcript, Replayer replayer, List<ScopeFrame> frames,
                           List<AssertionView> assertions, List<CheckView> checks,
                           Map<Integer, ModelSummary> models, Map<Integer, CoreSummary> cores,
                           List<SolverCandidate> candidates) {
        public ReplayState state() {
            return replayer.state;
        }
    }

    public record SolverCandidate(String stableId, String source, String value, String note) {
    }

    private RevisionAnalysis() {
    }

    public static Analysis analyze(String revisionId, String rawText,
                                   BoundedSolver.Params params, RevisionKind kind) {
        Transcript transcript = Transcript.parse(rawText);
        Replayer replayer = Replayer.run(transcript);
        ReplayState state = replayer.state;

        List<AssertionView> assertionViews = new ArrayList<>();
        for (ReplayState.Assertion a : allAssertionsByDepth(state)) {
            Map<String, String> labelTerms = new LinkedHashMap<>();
            a.labels.forEach((k, v) -> labelTerms.put(k, v.toString()));
            assertionViews.add(new AssertionView(a.entryId, a.raw, a.frameDepth,
                    Map.copyOf(labelTerms), true));
        }

        List<ScopeFrame> frames = new ArrayList<>();
        for (int d = 0; d <= state.depth(); d++) {
            frames.add(new ScopeFrame(d, List.of()));
        }

        Map<Integer, ModelSummary> models = new LinkedHashMap<>();
        Map<Integer, CoreSummary> cores = new LinkedHashMap<>();
        List<CheckView> checkViews = new ArrayList<>();
        List<SolverCandidate> candidates = new ArrayList<>();
        CoreReduction verifier = new CoreReduction(state, transcript);

        for (ReplayState.CheckPoint cp : state.checks()) {
            String modelId = null;
            String coreId = null;
            if (cp.modelEntryId != null) {
                modelId = org.witness.smt.store.Ids.derivedId(revisionId,
                        "local-subset-1.0.0", params.fingerprint(), "model", cp.commandEntryId);
                ModelCheck mc = verifier.verifyModel(cp, params);
                models.put(cp.sequence, summarizeModel(modelId, cp, transcript, state, mc, candidates));
            }
            if (cp.coreEntryId != null) {
                coreId = org.witness.smt.store.Ids.derivedId(revisionId,
                        "local-subset-1.0.0", params.fingerprint(), "core", cp.commandEntryId);
                CoreCheck cc = verifier.verifyCore(cp, params);
                cores.put(cp.sequence, summarizeCore(coreId, cc));
            }
            checkViews.add(new CheckView(cp.sequence, cp.commandEntryId, cp.responseEntryId,
                    cp.solverVerdict.name(), cp.depth, cp.assumptions, cp.activeAssertionIds, modelId, coreId));
        }

        return new Analysis(transcript, replayer, frames, assertionViews, checkViews,
                models, cores, candidates);
    }

    private static List<ReplayState.Assertion> allAssertionsByDepth(ReplayState state) {
        List<ReplayState.Assertion> out = new ArrayList<>();
        for (ReplayState.Assertion a : state.activeAssertions()) {
            out.add(a);
        }
        return out;
    }

    private static ModelSummary summarizeModel(String derivedId, ReplayState.CheckPoint cp,
                                               Transcript transcript, ReplayState state, ModelCheck mc,
                                               List<SolverCandidate> candidates) {
        List<AssertionEvalView> evals = new ArrayList<>();
        for (var e : mc.evals()) {
            evals.add(new AssertionEvalView(e.assertionId(), e.raw(), e.value(), e.tree()));
        }
        int respIndex = Integer.parseInt(cp.modelEntryId.substring(1));
        SExpr payload = transcript.entries().get(respIndex).payload;
        Model model = new ModelParser().parse(payload, cp.modelEntryId);
        List<ModelValueView> values = new ArrayList<>();
        for (ModelDef def : model.defs().values()) {
            values.add(new ModelValueView(def.name(), def.resultSort().name(),
                    def.isArray() ? def.array().toString() : Values.show(def.scalar()),
                    def.sourceEntryId(),
                    false,
                    def.resultSort() instanceof Sort.Unsupported || def.scalar() instanceof Values.UnknownV,
                    def.rawBody().toString()));
        }
        for (String missing : mc.missingSymbols()) {
            var info = state.lookup(missing);
            String sort = info == null ? "?" : info.sort.name();
            values.add(new ModelValueView(missing, sort, "#partial", cp.modelEntryId, true, false, null));
        }
        if (mc.status() == CoreReduction.Status.VERIFIED) {
            candidates.add(new SolverCandidate(derivedId + ":model", "solver-model",
                    "accepted", "all active assertions evaluate true under the model"));
        }
        return new ModelSummary(derivedId, mc.status().name(), evals, values,
                mc.unsupported(), mc.missingSymbols(), mc.diagnostics());
    }

    private static CoreSummary summarizeCore(String derivedId, CoreCheck cc) {
        List<AttemptView> attempts = new ArrayList<>();
        for (var a : cc.attempts()) {
            String stableId = derivedId + ":attempt:" + a.label();
            attempts.add(new AttemptView(stableId, a.label(), a.sourceAssertionId(),
                    a.status().name(), a.result(), a.reason()));
        }
        return new CoreSummary(derivedId, cc.status().name(), cc.unsatBySubset(),
                cc.sourcesPreserved(), cc.minimal(), cc.labels(), cc.unknownLabels(), attempts,
                cc.diagnostics());
    }
}
