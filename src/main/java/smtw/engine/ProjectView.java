package smtw.engine;

import smtw.verify.VerificationReport;

import java.util.List;

/** Everything the UI needs at once, including stale-but-readable history and as-of support. */
public record ProjectView(
        Project project,
        List<Blob> blobs,
        List<ParamVersion> paramVersions,
        ParamVersion activeParams,
        List<Event> events,
        List<DerivedSnapshot> snapshots,
        List<DecisionRecord> decisions,
        VerificationReport currentReport) {
}
