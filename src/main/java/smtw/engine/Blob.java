package smtw.engine;

/**
 * An immutable raw input. Once submitted it can never be overwritten; revisions are expressed
 * as a new blob with a parentBlobId. Raw text is stored verbatim.
 */
public record Blob(String id, String kind, String filename, String rawText,
                   String parentBlobId, long submittedAtMs, String inputSha) {

    public boolean isRevision() { return parentBlobId != null; }
}
