package chamber.sem;

import java.math.BigInteger;

/** Exact rational for the finite Real subset. */
public record Fraction(BigInteger num, BigInteger den) implements Comparable<Fraction> {

    public Fraction {
        if (den.signum() == 0) throw new ArithmeticException("zero denominator");
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }
        BigInteger g = num.gcd(den);
        if (!g.equals(BigInteger.ONE)) {
            num = num.divide(g);
            den = den.divide(g);
        }
    }

    public static Fraction of(BigInteger n) {
        return new Fraction(n, BigInteger.ONE);
    }

    public static Fraction of(long n) {
        return of(BigInteger.valueOf(n));
    }

    public static Fraction parse(String text) {
        if (text.contains(".")) {
            String[] parts = text.split("\\.");
            boolean neg = parts[0].startsWith("-");
            String wholePart = neg ? parts[0].substring(1) : parts[0];
            BigInteger whole = wholePart.isEmpty() ? BigInteger.ZERO : new BigInteger(wholePart);
            String fracDigits = parts.length > 1 ? parts[1] : "";
            BigInteger frac = fracDigits.isEmpty() ? BigInteger.ZERO : new BigInteger(fracDigits);
            BigInteger scale = BigInteger.TEN.pow(fracDigits.length());
            BigInteger num = whole.multiply(scale).add(frac);
            return new Fraction(neg ? num.negate() : num, scale);
        }
        return of(new BigInteger(text));
    }

    public Fraction add(Fraction o) {
        return new Fraction(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
    }

    public Fraction sub(Fraction o) {
        return new Fraction(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));
    }

    public Fraction mul(Fraction o) {
        return new Fraction(num.multiply(o.num), den.multiply(o.den));
    }

    public Fraction div(Fraction o) {
        if (o.num.signum() == 0) throw new ArithmeticException("division by zero");
        return new Fraction(num.multiply(o.den), den.multiply(o.num));
    }

    public Fraction neg() {
        return new Fraction(num.negate(), den);
    }

    public boolean isInteger() {
        return den.equals(BigInteger.ONE);
    }

    @Override public int compareTo(Fraction o) {
        return num.multiply(o.den).compareTo(o.num.multiply(den));
    }

    @Override public String toString() {
        return den.equals(BigInteger.ONE) ? num.toString() : num + "/" + den;
    }
}
