package room.testimony;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Json {
    public static final ObjectMapper M = new ObjectMapper();

    static {
        M.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    private Json() {}

    public static ObjectNode obj() {
        return M.createObjectNode();
    }

    public static ArrayNode arr() {
        return M.createArrayNode();
    }

    public static JsonNode parse(String s) {
        try {
            return M.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage(), e);
        }
    }

    public static String write(JsonNode n) {
        try {
            return M.writeValueAsString(n);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String pretty(JsonNode n) {
        try {
            return M.writerWithDefaultPrettyPrinter().writeValueAsString(n);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
