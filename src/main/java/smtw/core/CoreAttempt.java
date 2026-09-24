package smtw.core;

import java.util.List;

/** One reduction attempt: remove {@code removedLabels} from the core and record whether the rest is still unsat. */
public record CoreAttempt(String id, int ordinal, List<String> retainedLabels,
                          List<String> removedLabels, Result result, List<String> reasons,
                          int assignmentsTried, String algoVersion, String paramFingerprint) {

    public enum Result { STILL_UNSAT, BECAME_SAT, UNKNOWN }

    public CoreAttempt {
        retainedLabels = List.copyOf(retainedLabels);
        removedLabels = List.copyOf(removedLabels);
        reasons = List.copyOf(reasons);
    }
}
