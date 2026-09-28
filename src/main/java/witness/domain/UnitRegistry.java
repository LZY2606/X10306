package witness.domain;

import java.util.Map;

import witness.util.Rational;

/**
 * Tiny built-in conversion table for the case-pack units. Values missing from the
 * table are kept raw with a diagnostic instead of being dropped.
 */
public final class UnitRegistry {
    private record Factor(Rational multiplier, String base) {
    }

    private final Map<String, Factor> toBase;

    public UnitRegistry() {
        toBase = Map.of(
                "mm", new Factor(Rational.of(1, 1000), "m"),
                "cm", new Factor(Rational.of(1, 100), "m"),
                "m", new Factor(Rational.ONE, "m"),
                "ms", new Factor(Rational.of(1, 1000), "s"),
                "s", new Factor(Rational.ONE, "s"));
    }

    public void apply(Quantity q) {
        String unit = q.unit();
        if (unit.isEmpty()) {
            q.addDiagnostic("no-unit-declared: raw value '" + q.raw()
                    + "' retained without normalisation");
            return;
        }
        Factor f = toBase.get(unit);
        if (f == null) {
            q.addDiagnostic("unconvertible-unit: '" + unit + "' is not in the local conversion table; "
                    + "raw value '" + q.raw() + "' retained at origin");
            return;
        }
        try {
            Rational v = Rational.parse(q.raw());
            q.setNormalized(v.mul(f.multiplier()), f.base());
        } catch (RuntimeException e) {
            q.addDiagnostic("unparseable-quantity: " + e.getMessage());
        }
    }
}
