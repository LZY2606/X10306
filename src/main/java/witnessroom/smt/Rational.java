package witnessroom.smt;

import java.math.BigInteger;

/** Immutable exact rational, used for both Int and Real semantics. */
public final class Rational {
    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    private final BigInteger num;
    private final BigInteger den; // always positive

    private Rational(BigInteger num, BigInteger den) {
        if (den.signum() == 0) throw new ArithmeticException("zero denominator");
        if (den.signum() < 0) { num = num.negate(); den = den.negate(); }
        BigInteger g = num.abs().gcd(den);
        this.num = num.divide(g);
        this.den = den.divide(g);
    }

    public static Rational of(long n, long d) { return new Rational(BigInteger.valueOf(n), BigInteger.valueOf(d)); }
    public static Rational integer(long n) { return new Rational(BigInteger.valueOf(n), BigInteger.ONE); }
    public static Rational ofBig(BigInteger n) { return new Rational(n, BigInteger.ONE); }

    public static Rational parse(String token) {
        String t = token;
        if (t.startsWith("(- ") && t.endsWith(")")) t = "-" + t.substring(3, t.length() - 1).trim();
        int dot = t.indexOf('.');
        if (dot < 0) {
            if (t.startsWith("#x")) return new Rational(new BigInteger(t.substring(2), 16), BigInteger.ONE);
            if (t.startsWith("#b")) return new Rational(new BigInteger(t.substring(2), 2), BigInteger.ONE);
            return new Rational(new BigInteger(t), BigInteger.ONE);
        }
        String intPart = t.substring(0, dot);
        String frac = t.substring(dot + 1);
        if (intPart.isEmpty() || intPart.equals("-") || intPart.equals("+")) intPart += "0";
        BigInteger scale = BigInteger.TEN.pow(frac.length());
        BigInteger n = new BigInteger(intPart).multiply(scale)
                .add(new BigInteger((intPart.startsWith("-") ? "-" : "") + frac));
        return new Rational(n, scale);
    }

    public BigInteger numerator() { return num; }
    public BigInteger denominator() { return den; }
    public boolean isIntegral() { return den.equals(BigInteger.ONE); }
    public long longValueExact() {
        if (!isIntegral()) throw new ArithmeticException("not integral: " + this);
        return num.longValueExact();
    }

    public Rational add(Rational o) { return new Rational(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den)); }
    public Rational sub(Rational o) { return new Rational(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den)); }
    public Rational mul(Rational o) { return new Rational(num.multiply(o.num), den.multiply(o.den)); }
    public Rational div(Rational o) { return new Rational(num.multiply(o.den), den.multiply(o.num)); }
    public Rational negate() { return new Rational(num.negate(), den); }
    public Rational floor() {
        BigInteger[] qr = num.divideAndRemainder(den);
        if (qr[1].signum() < 0) qr[0] = qr[0].subtract(BigInteger.ONE);
        return new Rational(qr[0], BigInteger.ONE);
    }
    public int signum() { return num.signum(); }
    public int compareTo(Rational o) { return num.multiply(o.den).compareTo(o.num.multiply(den)); }

    @Override public boolean equals(Object o) {
        return o instanceof Rational r && num.equals(r.num) && den.equals(r.den);
    }
    @Override public int hashCode() { return num.hashCode() * 31 + den.hashCode(); }
    @Override public String toString() { return isIntegral() ? num.toString() : num + "/" + den; }
}
