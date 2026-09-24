package app;

import java.util.List;
import java.util.Map;

public final class Domain {
    private Domain() {
    }

    public record ProjectCreated(String projectId, String name, String caseId, String fixedSeed) {
    }

    public record RawQuantity(
            String id,
            String commandId,
            String symbol,
            String rawText,
            String unit,
            String coordinateBasis,
            Map<String, String> annotations
    ) {
    }

    public record CasePackage(
            String schemaVersion,
            String caseId,
            String title,
            String narrative,
            String transcript,
            List<RawQuantity> quantities
    ) {
    }

    public record MaterialCommitted(
            String materialId,
            String parentMaterialId,
            int version,
            String title,
            String transcript,
            List<RawQuantity> quantities,
            List<String> changedDirectSourceIds,
            String revisionReason
    ) {
    }

    public record RuleCommitted(
            String ruleId,
            String parentRuleId,
            int version,
            String algorithmVersion,
            Map<String, Object> parameters,
            String fingerprint,
            List<String> supportedTheories,
            List<String> explicitlyUnsupportedTheories,
            String revisionReason
    ) {
    }

    public record ResultComputed(
            String resultId,
            String resultKey,
            String kind,
            String status,
            String title,
            Object value,
            String unit,
            List<String> evidenceCommandIds,
            List<String> directSourceIds,
            List<String> dependencyKeys,
            List<DerivedNode> children,
            List<JudgmentBasis> judgments,
            List<Diagnostic> diagnostics,
            String materialId,
            String ruleId,
            String algorithmVersion,
            String parameterFingerprint,
            String contentFingerprint
    ) {
    }

    public record DerivedNode(
            String stableId,
            String kind,
            String status,
            String label,
            Object value,
            String unit,
            String rawRepresentation,
            List<String> sourceIds,
            List<DerivedNode> children,
            List<Diagnostic> diagnostics
    ) {
    }

    public record JudgmentBasis(
            String stableId,
            String code,
            String claim,
            String status,
            List<String> sourceIds,
            String specificationNote
    ) {
    }

    public record Diagnostic(
            String stableId,
            String severity,
            String code,
            String message,
            String rawRepresentation,
            List<String> sourceIds
    ) {
    }

    public record DecisionMade(
            String decisionId,
            String subjectKey,
            String subjectTitle,
            String verdict,
            String rationale,
            String replacesDecisionId,
            String conflictId
    ) {
    }

    public record ConflictDetected(
            String conflictId,
            String subjectKey,
            String firstDecisionId,
            String secondDecisionId,
            String reason
    ) {
    }

    public record ConflictResolved(
            String resolutionId,
            String conflictId,
            String winningDecisionId,
            String rejectedDecisionId,
            String reason
    ) {
    }

    public record DecisionUndone(
            String undoId,
            String decisionId,
            String subjectKey,
            String reason
    ) {
    }

    public record EventRecord(
            long seq,
            String eventId,
            String type,
            Object payload,
            String occurredAt
    ) {
    }

    public record ScopeView(
            String stableId,
            int depth,
            String enterCommandId,
            String label,
            List<String> activeAssertionIds,
            List<String> shadowedNames
    ) {
    }

    public record ProjectSummary(String projectId, String name, String caseId, long eventCount) {
    }
}
