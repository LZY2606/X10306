package witness.util;

import java.math.BigInteger;
import java.util.Objects;

/** Immutable exact rational number used by the local simplex engine. */
public final class Rational implements Comparable<Rational> {
    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);
    public static final Rational NEG_ONE = new Rational(BigInteger.ONE.negate(), BigInteger.ONE);

    private final BigInteger num;
    private final BigInteger den; // always positive

    public Rational(BigInteger num, BigInteger den) {
        if (den.signum() == 0) {
            throw new ArithmeticException("zero denominator");
        }
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }
        BigInteger g = num.gcd(den);
        if (g.signum() != 0) {
            num = num.divide(g);
            den = den.divide(g);
        }
        this.num = num;
        this.den = den;
    }

    public static Rational of(long n) {
        return new Rational(BigInteger.valueOf(n), BigInteger.ONE);
    }

    public static Rational of(long n, long d) {
        return new Rational(BigInteger.valueOf(n), BigInteger.valueOf(d));
    }

    public static Rational parse(String s) {
        String t = s.trim();
        int slash = t.indexOf('/');
        if (slash >= 0) {
            return new Rational(new BigInteger(t.substring(0, slash).trim()),
                    new BigInteger(t.substring(slash + 1).trim()));
        }
        if (t.contains(".") || t.contains("e") || t.contains("E")) {
            double d = Double.parseDouble(t);
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return new Rational(BigInteger.valueOf((long) d), BigInteger.ONE);
            }
            throw new NumberFormatException("non-integral decimal literal unsupported in exact engine: " + s);
        }
        return new Rational(new BigInteger(t), BigInteger.ONE);
    }

    public BigInteger numerator() {
        return num;
    }

    public BigInteger denominator() {
        return den;
    }

    public Rational add(Rational o) {
        return new Rational(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
    }

    public Rational sub(Rational o) {
        return new Rational(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));
    }

    public Rational mul(Rational o) {
        return new Rational(num.multiply(o.num), den.multiply(o.den));
    }

    public Rational div(Rational o) {
        if (o.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        return new Rational(num.multiply(o.den), den.multiply(o.num));
    }

    public Rational negate() {
        return new Rational(num.negate(), den);
    }

    public int signum() {
        return num.signum();
    }

    public boolean isIntegral() {
        return den.equals(BigInteger.ONE);
    }

    public double toDouble() {
        return new java.math.BigDecimal(num).divide(new java.math.BigDecimal(den),
                java.math.MathContext.DECIMAL64).doubleValue();
    }

    @Override
    public int compareTo(Rational o) {
        return num.multiply(o.den).compareTo(o.num.multiply(den));
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Rational)) {
            return false;
        }
        Rational r = (Rational) o;
        return num.equals(r.num) && den.equals(r.den);
    }

    @Override
    public int hashCode() {
        return Objects.hash(num, den);
    }

    @Override
    public String toString() {
        return den.equals(BigInteger.ONE) ? num.toString() : num + "/" + den;
    }
}
