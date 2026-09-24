package smtwitness.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import smtwitness.smt.Block;

import java.util.ArrayList;
import java.util.List;

/** Parses and strictly validates smt-testimony/v1 bundles. */
public final class BundleReader {
    private static final ObjectMapper M = new ObjectMapper();

    public record Read(Bundle bundle, List<String> diagnostics) {}

    public static Read read(String json) {
        List<String> problems = new ArrayList<>();
        JsonNode root;
        try {
            root = M.readTree(json);
        } catch (Exception ex) {
            throw new BundleFormatException(List.of("invalid JSON: " + firstMessage(ex)));
        }
        if (root == null || !root.isObject()) {
            throw new BundleFormatException(List.of("top level must be a JSON object"));
        }
        requireText(root, "format", Bundle.FORMAT, problems);
        requireText(root, "version", Bundle.VERSION, problems);
        String name = textOr(root, "name", "untitled");
        String scenario = textOr(root, "scenario", "");
        String description = textOr(root, "description", "");

        List<Block> blocks = new ArrayList<>();
        JsonNode blocksNode = root.get("blocks");
        if (blocksNode == null || !blocksNode.isArray()) {
            problems.add("blocks must be a non-empty JSON array");
        } else {
            if (blocksNode.isEmpty()) problems.add("blocks must be a non-empty JSON array");
            for (int i = 0; i < blocksNode.size(); i++) {
                JsonNode bn = blocksNode.get(i);
                if (!bn.isObject()) { problems.add("blocks[" + i + "] must be an object"); continue; }
                JsonNode typeNode = bn.get("type");
                JsonNode textNode = bn.get("text");
                String type = typeNode != null && typeNode.isTextual() ? typeNode.asText() : null;
                String text = textNode != null && textNode.isTextual() ? textNode.asText() : null;
                if (type == null || !Block.validTypes().contains(type)) {
                    problems.add("blocks[" + i + "].type must be one of " + Block.validTypes());
                }
                if (text == null) problems.add("blocks[" + i + "].text must be a string");
                if (type != null && Block.validTypes().contains(type) && text != null && !text.isBlank()) {
                    blocks.add(new Block(i, type, text));
                }
            }
            // sequence gaps in the *submitted* array are reported; originals stay verbatim
            boolean sawLogic = blocks.stream().anyMatch(b -> b.text().contains("set-logic"));
            if (!sawLogic) problems.add("transcript must contain a set-logic command (logic declaration preservation)");
            boolean sawCheck = blocks.stream().anyMatch(b ->
                    b.text().contains("check-sat") );
            if (!sawCheck) problems.add("transcript must contain at least one check-sat / check-sat-assuming");
        }

        List<Bundle.Quantity> quantities = new ArrayList<>();
        JsonNode qNode = root.get("quantities");
        if (qNode != null) {
            if (!qNode.isArray()) {
                problems.add("quantities must be an array");
            } else {
                for (int i = 0; i < qNode.size(); i++) {
                    JsonNode q = qNode.get(i);
                    if (!q.isObject()) { problems.add("quantities[" + i + "] must be an object"); continue; }
                    String sym = optionalText(q, "symbol");
                    String rawValue = optionalText(q, "rawValue");
                    String rawUnit = optionalText(q, "rawUnit");
                    String basis = optionalText(q, "basis");
                    Long lo = optionalLong(q, "domainLo", problems, "quantities[" + i + "].domainLo");
                    Long hi = optionalLong(q, "domainHi", problems, "quantities[" + i + "].domainHi");
                    String note = optionalText(q, "note");
                    if (sym == null) problems.add("quantities[" + i + "].symbol is required");
                    if (rawValue == null) problems.add("quantities[" + i + "].rawValue is required (raw representation preserved)");
                    if (rawUnit == null) problems.add("quantities[" + i + "].rawUnit is required; use \"dimensionless\" when none applies");
                    if (sym != null && rawValue != null && rawUnit != null) {
                        quantities.add(new Bundle.Quantity(sym, rawValue, rawUnit, basis, lo, hi, note));
                    }
                }
            }
        }

        if (!problems.isEmpty()) throw new BundleFormatException(problems);
        return new Read(new Bundle(Bundle.FORMAT, Bundle.VERSION, name, scenario, description,
                blocks, quantities), List.of());
    }

    private static String firstMessage(Exception ex) {
        return ex.getMessage() == null ? "parse failure" : ex.getMessage();
    }

    private static void requireText(JsonNode root, String field, String expected, List<String> problems) {
        JsonNode n = root.get(field);
        if (n == null || !n.isTextual()) { problems.add(field + " must be the string \"" + expected + "\""); return; }
        if (!expected.equals(n.asText())) problems.add(field + " must be \"" + expected + "\" (found \"" + n.asText() + "\")");
    }

    private static String textOr(JsonNode n, String field, String dflt) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() ? v.asText() : dflt;
    }

    private static String optionalText(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static Long optionalLong(JsonNode n, String field, List<String> problems, String label) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        if (v.isIntegralNumber()) return v.asLong();
        problems.add(label + " must be an integer");
        return null;
    }
}
