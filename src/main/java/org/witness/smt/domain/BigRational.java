package org.witness.smt.domain;

import java.math.BigInteger;
import java.util.Objects;

/** Arbitrary precision rationals for the Int/Real bounded semantics. */
public final class BigRational implements Comparable<BigRational> {

    public static final BigRational ZERO = new BigRational(BigInteger.ZERO, BigInteger.ONE);
    public static final BigRational ONE = new BigRational(BigInteger.ONE, BigInteger.ONE);

    public final BigInteger num;
    public final BigInteger den;

    private BigRational(BigInteger num, BigInteger den) {
        this.num = num;
        this.den = den;
    }

    public static BigRational of(BigInteger num, BigInteger den) {
        if (den.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }
        BigInteger g = num.abs().gcd(den);
        return new BigRational(num.divide(g), den.divide(g));
    }

    public static BigRational integer(long v) {
        return of(BigInteger.valueOf(v), BigInteger.ONE);
    }

    public static BigRational parse(String token) {
        // SMT-LIB decimals: "3.14" ; integers: "3" or "(- 5)" are handled by term eval.
        if (token.contains("/")) {
            String[] parts = token.split("/", 2);
            return of(new BigInteger(parts[0]), new BigInteger(parts[1]));
        }
        int dot = token.indexOf('.');
        if (dot < 0) {
            return of(new BigInteger(token), BigInteger.ONE);
        }
        String intPart = token.substring(0, dot);
        String frac = token.substring(dot + 1);
        BigInteger n = new BigInteger((intPart.isEmpty() ? "0" : intPart) + frac);
        BigInteger d = BigInteger.TEN.pow(frac.length());
        return of(n, d);
    }

    public boolean isIntegral() {
        return den.equals(BigInteger.ONE);
    }

    public BigRational add(BigRational o) {
        return of(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
    }

    public BigRational sub(BigRational o) {
        return of(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));
    }

    public BigRational mul(BigRational o) {
        return of(num.multiply(o.num), den.multiply(o.den));
    }

    public BigRational div(BigRational o) {
        if (o.num.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }
        return of(num.multiply(o.den), den.multiply(o.num));
    }

    public BigRational negate() {
        return of(num.negate(), den);
    }

    @Override
    public int compareTo(BigRational o) {
        return num.multiply(o.den).compareTo(o.num.multiply(den));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BigRational r && compareTo(r) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(num, den);
    }

    @Override
    public String toString() {
        if (isIntegral()) {
            return num.toString();
        }
        return num + "/" + den;
    }
}
