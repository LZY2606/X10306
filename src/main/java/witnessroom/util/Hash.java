package witnessroom.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Hash {
    private Hash() {}

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String shortId(String prefix, String... parts) {
        return prefix + "-" + sha256(String.join("|", parts)).substring(0, 16);
    }
}
