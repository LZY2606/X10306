package smtw.model;

/**
 * Unit or coordinate-basis annotation for a domain quantity.
 * Annotations live ONLY in the import sidecar: raw values are never rewritten at import time.
 * The derived layer may normalise to {@code canonicalUnit} using {@code toCanonical};
 * when no conversion exists the value remains in place with a diagnostic (never dropped).
 */
public record MeasureAnn(String symbol, String unit, String canonicalUnit,
                         Double toCanonical, String coordinateBasis) {

    public boolean hasUnit() { return unit != null && !unit.isBlank(); }
    public boolean hasBasis() { return coordinateBasis != null && !coordinateBasis.isBlank(); }
    public boolean convertible() { return hasUnit() && canonicalUnit != null && toCanonical != null; }
}
