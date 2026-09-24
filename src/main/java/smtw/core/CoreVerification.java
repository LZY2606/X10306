package smtw.core;

import java.util.List;

/** Full result of verifying a get-unsat-core response at a checkpoint. */
public record CoreVerification(
        String id,
        String checkKey,
        List<String> reportedLabels,
        List<String> unresolvedLabels,
        List<LabelSource> sources,
        Verdict verdict,
        List<String> reasons,
        List<CoreCandidate> candidates,
        String algoVersion,
        String paramFingerprint) {

    public enum Verdict { VALID_UNSAT, INVALID_SAT, UNKNOWN }

    public CoreVerification {
        reportedLabels = List.copyOf(reportedLabels);
        unresolvedLabels = List.copyOf(unresolvedLabels);
        sources = List.copyOf(sources);
        reasons = List.copyOf(reasons);
        candidates = List.copyOf(candidates);
    }
}
