package smtw.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Stable identity helpers. IDs are derived from CONTENT (hash of raw bytes + ordinal structure),
 * so the same material gets the same id in every database, after export/delete/reimport.
 */
public final class StableId {

    private StableId() {}

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shortHash(String s) {
        return sha256Hex(s).substring(0, 16);
    }
}
