package witness;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable content-addressed identities. Same content -> same id, across export/import. */
public final class Ids {
    private Ids() {}

    public static String of(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                md.update((byte) 0x1f);
                md.update(p.getBytes(StandardCharsets.UTF_8));
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shortId(String id) {
        return id == null ? "-" : id.substring(0, Math.min(10, id.length()));
    }
}
