package smtwitness.engine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Derived normalization layer for domain quantities.
 * Raw representations stay at the import layer; conversions here are versioned and
 * every output keeps its basis. Unconvertible values stay in place with a diagnostic.
 */
public final class Units {
    private Units() {}

    public static final String ALGO = "units/si-linear-1";

    private record UnitFactor(BigDecimal factor, String si) {}

    private static final Map<String, UnitFactor> FACTORS = new LinkedHashMap<>();
    static {
        FACTORS.put("m",   new UnitFactor(BigDecimal.ONE, "m"));
        FACTORS.put("cm",  new UnitFactor(new BigDecimal("0.01"), "m"));
        FACTORS.put("mm",  new UnitFactor(new BigDecimal("0.001"), "m"));
        FACTORS.put("km",  new UnitFactor(new BigDecimal("1000"), "m"));
        FACTORS.put("s",   new UnitFactor(BigDecimal.ONE, "s"));
        FACTORS.put("ms",  new UnitFactor(new BigDecimal("0.001"), "s"));
        FACTORS.put("kg",  new UnitFactor(BigDecimal.ONE, "kg"));
        FACTORS.put("g",   new UnitFactor(new BigDecimal("0.001"), "kg"));
        FACTORS.put("dimensionless", new UnitFactor(BigDecimal.ONE, "dimensionless"));
    }

    public record Normalized(String rawValue, String rawUnit, String basis,
                             String siValue, String siUnit, Double numeric, String diagnostic) {
        public boolean convertible() { return diagnostic == null; }
    }

    public static Normalized normalize(String rawValue, String rawUnit, String basis) {
        String num = rawValue;
        UnitFactor f = FACTORS.get(rawUnit);
        if (f == null) {
            return new Normalized(rawValue, rawUnit, basis, null, null, null,
                    "unit '" + rawUnit + "' has no SI conversion in " + ALGO + "; raw value retained in place");
        }
        try {
            BigDecimal value = new BigDecimal(num.trim());
            BigDecimal si = value.multiply(f.factor);
            if (basis != null && basis.startsWith("index1")) {
                si = si.subtract(BigDecimal.ONE);
            }
            double d;
            try {
                d = si.doubleValue();
            } catch (NumberFormatException ex) {
                d = Double.NaN;
            }
            return new Normalized(rawValue, rawUnit, basis, si.stripTrailingZeros().toPlainString(),
                    f.si, Double.isFinite(d) ? d : null, null);
        } catch (NumberFormatException ex) {
            return new Normalized(rawValue, rawUnit, basis, null, null, null,
                    "rawValue '" + rawValue + "' is not numeric for unit '" + rawUnit + "'; retained in place");
        }
    }

    /** Returns normalized integer domain coordinates for an array/index basis annotation, if convertible. */
    public static Long coordinateOrNull(String rawValue, String rawUnit, String basis) {
        Normalized n = normalize(rawValue, rawUnit, basis);
        if (!n.convertible()) return null;
        try {
            return new BigDecimal(n.siValue()).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException ex) {
            return null;
        }
    }
}
