package smtw.scope;

import java.util.List;

/**
 * A deterministic record of how the scope stack reacts to ONE transcript command.
 * Every displayed row of the scope view carries its own stable id.
 */
public record ScopeEvent(
        String id,
        int commandOrdinal,
        String commandHead,
        int levelBefore,
        int levelAfter,
        String action,                 // PUSH / POP / ASSERT / ASSUME-CHECK / DECLARE / ...
        List<String> diagnostics,
        List<String> activeNamedAssertionsAfter) {

    public ScopeEvent {
        diagnostics = List.copyOf(diagnostics);
        activeNamedAssertionsAfter = List.copyOf(activeNamedAssertionsAfter);
    }
}
