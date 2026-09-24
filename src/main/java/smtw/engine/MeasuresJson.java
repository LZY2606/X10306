package smtw.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import smtw.model.MeasureAnn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the unit / coordinate-basis sidecar. This is import-time METADATA:
 * raw SMT values are never rewritten; the derived layer uses {@code toCanonical} for display.
 */
public final class MeasuresJson {

    private MeasuresJson() {}

    public static Map<String, MeasureAnn> parse(String json) {
        try {
            List<Map<String, Object>> rows = Json.io().readValue(json, new TypeReference<>() {});
            Map<String, MeasureAnn> out = new LinkedHashMap<>();
            for (Map<String, Object> r : rows) {
                MeasureAnn a = new MeasureAnn(
                        (String) r.get("symbol"),
                        (String) r.get("unit"),
                        (String) r.get("canonicalUnit"),
                        r.get("toCanonical") == null ? null : ((Number) r.get("toCanonical")).doubleValue(),
                        (String) r.get("coordinateBasis"));
                out.put(a.symbol(), a);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid measures sidecar: " + e.getMessage(), e);
        }
    }
}
