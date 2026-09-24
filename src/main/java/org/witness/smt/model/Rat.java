package org.witness.smt.model;

import java.math.BigInteger;

/** Arbitrary precision rational number used by the local arithmetic fragment. */
public final class Rat implements Comparable<Rat> {
    public static final Rat ZERO = new Rat(BigInteger.ZERO, BigInteger.ONE);
    public static final Rat ONE = new Rat(BigInteger.ONE, BigInteger.ONE);
    public static final Rat MONE = new Rat(BigInteger.ONE.negate(), BigInteger.ONE);

    public final BigInteger num;
    public final BigInteger den; // always positive

    public Rat(BigInteger num, BigInteger den) {
        if (den.signum() == 0) throw new ArithmeticException("zero denominator");
        if (den.signum() < 0) { num = num.negate(); den = den.negate(); }
        BigInteger g = num.abs().gcd(den);
        this.num = num.divide(g);
        this.den = den.divide(g);
    }

    public static Rat of(long n) { return new Rat(BigInteger.valueOf(n), BigInteger.ONE); }
    public static Rat of(long n, long d) { return new Rat(BigInteger.valueOf(n), BigInteger.valueOf(d)); }

    public static Rat parse(String token) {
        int slash = token.indexOf('/');
        if (slash >= 0)
            return new Rat(new BigInteger(token.substring(0, slash)), new BigInteger(token.substring(slash + 1)));
        if (token.endsWith(".0")) token = token.substring(0, token.length() - 2);
        int dot = token.indexOf('.');
        if (dot >= 0) {
            String whole = token.substring(0, dot);
            String frac = token.substring(dot + 1);
            BigInteger n = new BigInteger(whole + frac);
            BigInteger d = BigInteger.TEN.pow(frac.length());
            return new Rat(n, d);
        }
        return new Rat(new BigInteger(token), BigInteger.ONE);
    }

    public Rat add(Rat o) { return new Rat(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den)); }
    public Rat sub(Rat o) { return new Rat(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den)); }
    public Rat mul(Rat o) { return new Rat(num.multiply(o.num), den.multiply(o.den)); }
    public Rat div(Rat o) { return new Rat(num.multiply(o.den), den.multiply(o.num)); }
    public Rat neg() { return new Rat(num.negate(), den); }
    public boolean isIntegral() { return den.equals(BigInteger.ONE); }
    public BigInteger floor() {
        BigInteger[] qr = num.divideAndRemainder(den);
        if (qr[1].signum() < 0) qr[0] = qr[0].subtract(BigInteger.ONE);
        return qr[0];
    }
    public boolean lt(Rat o) { return num.multiply(o.den).compareTo(o.num.multiply(den)) < 0; }

    @Override public int compareTo(Rat o) { return num.multiply(o.den).compareTo(o.num.multiply(den)); }
    @Override public boolean equals(Object o) { return o instanceof Rat r && compareTo(r) == 0; }
    @Override public int hashCode() { return num.multiply(BigInteger.valueOf(31)).add(den).hashCode(); }
    @Override public String toString() { return den.equals(BigInteger.ONE) ? num.toString() : num + "/" + den; }
}
