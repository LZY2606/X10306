package chamber.smt;

import chamber.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** Solver responses, aligned by ordinal with the query commands of a session.
 *  Raw model values keep their original textual representation; normalisation
 *  happens only in the derived layer. Quantities (e.g. solver time) carry units. */
public final class Responses {

    public record Quantity(String raw, String unit) {}

    public record ModelEntry(String name, String sort, String valueRaw) {}

    public record ValuePair(String termRaw, String valueRaw) {}

    public record Response(
            int ordinal,
            String kind,          // check-sat | get-model | get-unsat-core | get-value
            String verdict,       // sat | unsat | unknown | null
            List<String> core,    // for get-unsat-core
            List<ModelEntry> model,
            List<ValuePair> values,
            Quantity time,
            String error) {}

    public static final class ResponseFormatException extends RuntimeException {
        public ResponseFormatException(String message) {
            super(message);
        }
    }

    public static List<Response> parse(String json) {
        JsonNode root;
        try {
            root = Json.read(json);
        } catch (IllegalArgumentException e) {
            throw new ResponseFormatException("responses are not valid JSON: " + e.getMessage());
        }
        JsonNode arr = root.has("responses") ? root.get("responses") : root;
        if (!arr.isArray()) {
            throw new ResponseFormatException("expected a JSON array or an object with a 'responses' array");
        }
        List<Response> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonNode n = arr.get(i);
            if (!n.isObject()) throw new ResponseFormatException("response #" + i + " is not an object");
            String kind = text(n, "kind");
            if (kind == null) throw new ResponseFormatException("response #" + i + " misses 'kind'");
            List<String> core = new ArrayList<>();
            if (n.has("core")) {
                if (!n.get("core").isArray()) throw new ResponseFormatException("response #" + i + ": 'core' must be an array");
                n.get("core").forEach(c -> core.add(c.asText()));
            }
            List<ModelEntry> model = new ArrayList<>();
            if (n.has("model")) {
                if (!n.get("model").isArray()) throw new ResponseFormatException("response #" + i + ": 'model' must be an array");
                for (JsonNode m : n.get("model")) {
                    String name = text(m, "name");
                    String value = text(m, "value");
                    if (name == null || value == null) {
                        throw new ResponseFormatException("response #" + i + ": model entry needs 'name' and 'value'");
                    }
                    model.add(new ModelEntry(name, text(m, "sort"), value));
                }
            }
            List<ValuePair> values = new ArrayList<>();
            if (n.has("values")) {
                for (JsonNode v : n.get("values")) {
                    values.add(new ValuePair(text(v, "term"), text(v, "value")));
                }
            }
            Quantity time = null;
            if (n.has("time")) {
                JsonNode t = n.get("time");
                time = new Quantity(t.has("raw") ? t.get("raw").asText() : t.asText(),
                        t.has("unit") ? t.get("unit").asText() : "ms");
            }
            out.add(new Response(i, kind, text(n, "verdict"), core, model, values, time, text(n, "error")));
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        return n.has(field) && !n.get(field).isNull() ? n.get(field).asText() : null;
    }
}
