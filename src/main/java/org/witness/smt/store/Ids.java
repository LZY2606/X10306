package org.witness.smt.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable identity minting. Source-derived ids are content-addressed so
 * export/delete/import reproduces them byte for byte; sequential ids are
 * scoped by a project key and keep order.
 */
public final class Ids {

    private Ids() {
    }

    public static String sha12(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                md.update((byte) 0x1f);
                md.update(p.getBytes(StandardCharsets.UTF_8));
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String importId(String transcriptText) {
        return "imp_" + sha12("import", transcriptText);
    }

    public static String revisionId(String importId, int version) {
        return "rev_" + sha12("revision", importId, String.valueOf(version));
    }

    public static String derivedId(String revisionId, String algorithmVersion,
                                   String paramFingerprint, String kind, String sourceId) {
        return "drv_" + sha12("derived", revisionId, algorithmVersion, paramFingerprint, kind, sourceId);
    }

    public static String eventId(String projectId, int ordinal) {
        return "ev_" + projectId + "_" + ordinal;
    }
}
