package smtroom.core;

import java.math.BigDecimal;
import java.util.*;

/**
 * Domain quantities carry units or coordinate bases. Raw representation is
 * preserved at import; normalization happens only in this derived layer.
 * Unconvertible values stay in place with a diagnostic — never dropped.
 */
public final class Quantities {

    public record Quantity(String name, String raw, String value, String unit,
                           String canonicalValue, String canonicalUnit, String diagnostic) {}

    private static final Map<String, String[]> UNITS = Map.ofEntries(
            Map.entry("ms", new String[]{"s", "0.001"}),
            Map.entry("s", new String[]{"s", "1"}),
            Map.entry("us", new String[]{"s", "0.000001"}),
            Map.entry("MiB", new String[]{"byte", "1048576"}),
            Map.entry("KiB", new String[]{"byte", "1024"}),
            Map.entry("MB", new String[]{"byte", "1000000"}),
            Map.entry("byte", new String[]{"byte", "1"}),
            Map.entry("clause", new String[]{"clause", "1"}),
            Map.entry("clauses", new String[]{"clause", "1"}),
            Map.entry("decision", new String[]{"decision", "1"}),
            Map.entry("decisions", new String[]{"decision", "1"})
    );

    /** Parse "137 ms" / "48 MiB" / "7.5 solver-units" / "sat" etc. */
    public static Quantity normalize(String name, String raw) {
        String trimmed = raw.strip();
        String[] parts = trimmed.split("\\s+", 2);
        String numPart = parts.length == 2 ? parts[0] : null;
        String unitPart = parts.length == 2 ? parts[1] : null;
        if (numPart == null || !numPart.matches("-?\\d+(\\.\\d+)?")) {
            // not a number+unit pair: keep raw, note coordinate basis if given as "x @ basis"
            return new Quantity(name, trimmed, trimmed, null, null, null,
                    "kept-raw: not a numeric quantity with unit");
        }
        BigDecimal v = new BigDecimal(numPart);
        String[] conv = UNITS.get(unitPart);
        if (conv == null) {
            return new Quantity(name, trimmed, numPart, unitPart, null, null,
                    "unconvertible-unit: " + unitPart + " (kept in place, not dropped)");
        }
        BigDecimal canon = v.multiply(new BigDecimal(conv[1])).stripTrailingZeros();
        return new Quantity(name, trimmed, numPart, unitPart, canon.toPlainString(), conv[0], null);
    }

    /** Parse the quantities section of an import document. */
    public static List<Quantity> parseSection(String section) {
        List<Quantity> out = new ArrayList<>();
        for (String line : section.split("\n")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int eq = l.indexOf('=');
            if (eq < 0) {
                out.add(new Quantity(l, l, l, null, null, null, "malformed-line: missing '='"));
                continue;
            }
            out.add(normalize(l.substring(0, eq).strip(), l.substring(eq + 1).strip()));
        }
        return out;
    }
}
