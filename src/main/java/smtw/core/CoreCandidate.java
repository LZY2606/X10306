package smtw.core;

import java.util.List;

/**
 * A candidate core derived from the solver response. Candidates are first-class data:
 * the original non-minimal core is always retained, reduction adds minimal candidates,
 * and adoption is a separate human decision recorded in the event log.
 */
public record CoreCandidate(String id, String kind, List<String> labels,
                            List<CoreAttempt> attempts, boolean minimalUnderSubset,
                            String algoVersion, String paramFingerprint) {

    public CoreCandidate {
        labels = List.copyOf(labels);
        attempts = List.copyOf(attempts);
    }
}
