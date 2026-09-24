package smtw.engine;

/**
 * One materialised derived value. {@code current} means it is produced by the newest compatible
 * input+params. When a local input changes, only dependent snapshots stop being current; they
 * remain readable as stale history and can never masquerade as the current result.
 */
public record DerivedSnapshot(
        String id,
        DerivedKey key,
        String kind,                       // CHECK / MODEL_TREE / CORE / SCOPE
        String payloadJson,
        String directSourceEventId,
        long producedAtMs,
        boolean current,
        String staleReason) {
}
