package smtw.engine;

/** Stable key of a derived object: subject id + producing blob versions + param version + algo. */
public record DerivedKey(String subject, String transcriptBlobId,
                         String responseBlobId, String modelBlobId,
                         String paramVersionId, String algoVersion) {

    public String stableId() {
        return "der:" + StableId.shortHash(
                subject + "|" + transcriptBlobId + "|" + responseBlobId + "|" + modelBlobId
                        + "|" + paramVersionId + "|" + algoVersion);
    }
}
