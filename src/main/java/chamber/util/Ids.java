package chamber.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Stable, content-derived identities. The same logical content always yields the same id,
 *  so identities survive export / delete / re-import unchanged. */
public final class Ids {
    private Ids() {}

    public static String of(String prefix, String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String p : parts) {
                md.update(p.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0x1f);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(prefix).append('-');
            for (int i = 0; i < 9; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
