package smtvault.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable, content-derived identities that survive export/delete/re-import. */
public final class Ids {
    private Ids() {
    }

    public static String hash(String kind, String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((kind + "\u0000" + content).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(kind.substring(0, Math.min(3, kind.length())));
            for (int i = 0; i < 10; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
