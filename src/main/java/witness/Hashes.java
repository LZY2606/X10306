package witness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-256 based stable identifiers and event-hash chaining. */
public final class Hashes {
    private Hashes() {}

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Short stable id derived from type and canonical content. */
    public static String stableId(String type, String canonicalContent) {
        return type + "_" + sha256Hex(type + "\u0000" + canonicalContent).substring(0, 16);
    }
}
