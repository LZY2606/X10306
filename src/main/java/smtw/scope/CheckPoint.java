package smtw.scope;

import java.util.List;

/**
 * Snapshot at a check-sat / check-sat-assuming command: the scope stack, globally active named
 * assertions and the one-shot assumptions (only valid for this single check).
 */
public record CheckPoint(
        String id,
        int commandOrdinal,
        boolean assuming,
        List<String> assumptionLiterals,
        List<List<String>> scopeStackLevels,   // labels visible per level, bottom-up
        List<Binding> activeAssertions,
        List<String> resolvedAssumptionLabels,
        List<String> unresolvedAssumptions,
        List<String> diagnostics) {

    public CheckPoint {
        assumptionLiterals = List.copyOf(assumptionLiterals);
        scopeStackLevels = List.copyOf(scopeStackLevels);
        activeAssertions = List.copyOf(activeAssertions);
        resolvedAssumptionLabels = List.copyOf(resolvedAssumptionLabels);
        unresolvedAssumptions = List.copyOf(unresolvedAssumptions);
        diagnostics = List.copyOf(diagnostics);
    }
}
