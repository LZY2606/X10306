package org.witness.smt.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unit / coordinate-basis registry attached to a transcript import.
 *
 * <p>Import only preserves the raw representation (e.g. {@code %unit x m}).
 * Normalisation is a derived action: a converter table turns compatible units
 * into a base unit. Unconvertible values stay at their origin with a
 * diagnostic instead of being dropped.
 */
public final class Units {

    public record Basis(String symbol, String rawUnit, String rawText) {
    }

    /** name -> factor to base unit within a dimension. Base units map to 1. */
    private final Map<String, Double> lengthBase = Map.of(
            "m", 1.0, "cm", 0.01, "km", 1000.0, "mm", 0.001);
    private final Map<String, Double> timeBase = Map.of(
            "s", 1.0, "ms", 0.001, "min", 60.0, "h", 3600.0);

    private final Map<String, Basis> basis = new LinkedHashMap<>();
    private final Map<String, String> coordinateBasis = new LinkedHashMap<>();

    public void putUnit(String symbol, String unit) {
        basis.put(symbol, new Basis(symbol, unit, unit));
    }

    public void putCoordinateBasis(String symbol, String frame) {
        coordinateBasis.put(symbol, frame);
    }

    public Basis basisOf(String symbol) {
        return basis.get(symbol);
    }

    public String coordinateBasisOf(String symbol) {
        return coordinateBasis.get(symbol);
    }

    public Set<String> symbols() {
        return basis.keySet();
    }

    public double factor(String unit) {
        if (lengthBase.containsKey(unit)) {
            return lengthBase.get(unit);
        }
        if (timeBase.containsKey(unit)) {
            return timeBase.get(unit);
        }
        throw new IllegalArgumentException("no conversion known for unit '" + unit + "'");
    }

    public String dimension(String unit) {
        if (lengthBase.containsKey(unit)) {
            return "length";
        }
        if (timeBase.containsKey(unit)) {
            return "time";
        }
        return "unknown";
    }

    /**
     * Derived-only conversion. Returns {@code null} with a diagnostic when the
     * value cannot be converted; callers keep the original value at origin.
     */
    public double normalize(String symbol, double rawValue, StringBuilder diagnostic) {
        Basis b = basis.get(symbol);
        if (b == null) {
            diagnostic.append("symbol '").append(symbol).append("' has no declared unit basis");
            return Double.NaN;
        }
        Double factor;
        if (lengthBase.containsKey(b.rawUnit)) {
            factor = lengthBase.get(b.rawUnit);
        } else if (timeBase.containsKey(b.rawUnit)) {
            factor = timeBase.get(b.rawUnit);
        } else {
            diagnostic.append("unit '").append(b.rawUnit).append("' of '")
                    .append(symbol).append("' is not convertible to a base unit");
            return Double.NaN;
        }
        return rawValue * factor;
    }
}
