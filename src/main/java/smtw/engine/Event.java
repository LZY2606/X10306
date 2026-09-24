package smtw.engine;

import java.util.List;

/**
 * Append-only, machine-readable project event. The event log is the authoritative history:
 * materialised derived results can always be rebuilt by replaying it. Returning to any old
 * event restores the dependency versions and visible conclusions as of that event.
 */
public record Event(
        String id,
        long seq,
        long atMs,
        String type,
        String blobId,
        String parentBlobId,
        String paramVersionId,
        String subject,                  // stable id of the affected object (check / core / candidate ...)
        String decision,                 // ACCEPT / REJECT / ADJUST / CONFLICT / RESOLVE / null
        String payloadJson,
        List<String> invalidatedSubjects,
        String rationale) {

    public Event { invalidatedSubjects = List.copyOf(invalidatedSubjects); }

    public static final String PROJECT_CREATED = "PROJECT_CREATED";
    public static final String INPUT_SUBMITTED = "INPUT_SUBMITTED";
    public static final String PARAM_REVISED = "PARAM_REVISED";
    public static final String REPLAYED = "REPLAYED";
    public static final String DERIVED = "DERIVED";
    public static final String DECISION = "DECISION";
    public static final String CONFLICT = "CONFLICT";
    public static final String RESOLVED = "RESOLVED";
    public static final String UNDO = "UNDO";
    public static final String EXPORTED = "EXPORTED";
}
