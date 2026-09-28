package witness.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import witness.util.Rational;

/**
 * A domain numeric value. The raw SMT-LIB representation is always preserved.
 * Unit/coordinate basis metadata travels with the value. Normalisation only happens
 * in the derived layer; unconvertible values stay in place with diagnostics.
 */
public final class Quantity {
    private final String raw;
    private final String unit;
    private final String coordinateBasis;
    private Rational normalizedValue;
    private String normalizedUnit;
    private final List<String> diagnostics = new ArrayList<>();

    public Quantity(String raw, String unit, String coordinateBasis) {
        this.raw = Objects.requireNonNull(raw, "raw");
        this.unit = unit == null ? "" : unit;
        this.coordinateBasis = coordinateBasis == null ? "" : coordinateBasis;
    }

    public String raw() {
        return raw;
    }

    public String unit() {
        return unit;
    }

    public String coordinateBasis() {
        return coordinateBasis;
    }

    public boolean hasBasis() {
        return !unit.isEmpty() || !coordinateBasis.isEmpty();
    }

    public Rational normalizedValue() {
        return normalizedValue;
    }

    public String normalizedUnit() {
        return normalizedUnit;
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    void setNormalized(Rational value, String unit) {
        this.normalizedValue = value;
        this.normalizedUnit = unit;
    }

    void addDiagnostic(String d) {
        this.diagnostics.add(d);
    }
}
