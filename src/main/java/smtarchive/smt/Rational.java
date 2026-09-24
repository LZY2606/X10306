package smtarchive.smt;

import java.util.Objects;

/** Exact rational arithmetic. No floating point is ever used for domain values. */
public final class Rational implements Comparable<Rational> {
    public static final Rational ZERO = new Rational(0, 1);
    public static final Rational ONE = new Rational(1, 1);
    public static final Rational MINUS_ONE = new Rational(-1, 1);

    private final long num;
    private final long den; // always positive

    private Rational(long num, long den) {
        if (den == 0) throw new ArithmeticException("zero denominator");
        if (den < 0) { num = -num; den = -den; }
        long g = gcd(Math.abs(num), den);
        this.num = num / g;
        this.den = den / g;
    }

    public static Rational of(long n, long d) { return new Rational(n, d); }
    public static Rational of(long n) { return new Rational(n, 1); }

    /** Parses integers, decimals and ratios a/b, preserving the original spelling elsewhere. */
    public static Rational parse(String s) {
        s = s.trim();
        if (s.contains("/")) {
            String[] p = s.split("/", 2);
            return of(parseLongTrim(p[0]), parseLongTrim(p[1]));
        }
        if (s.contains(".")) {
            boolean neg = s.startsWith("-");
            String t = neg ? s.substring(1) : s;
            String[] p = t.split("\\.", 2);
            int k = p[1].length();
            long whole = p[0].isEmpty() ? 0 : Long.parseLong(p[0]);
            long frac = p[1].isEmpty() ? 0 : Long.parseLong(p[1]);
            long scale = pow10(k);
            long n = whole * scale + frac;
            return of(neg ? -n : n, scale);
        }
        return of(Long.parseLong(s), 1);
    }

    private static long parseLongTrim(String x) { return Long.parseLong(x.trim()); }
    private static long pow10(int k) { long v = 1; for (int i = 0; i < k; i++) v *= 10; return v; }

    private static long gcd(long a, long b) { return b == 0 ? a : gcd(b, a % b); }

    public Rational add(Rational o) { return of(num * o.den + o.num * den, den * o.den); }
    public Rational sub(Rational o) { return of(num * o.den - o.num * den, den * o.den); }
    public Rational mul(Rational o) { return of(num * o.num, den * o.den); }
    public Rational neg() { return of(-num, den); }
    public boolean isZero() { return num == 0; }
    public boolean isNegative() { return num < 0; }
    public long numerator() { return num; }
    public long denominator() { return den; }

    @Override public int compareTo(Rational o) {
        return Long.compare(num * o.den, o.num * den);
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Rational r)) return false;
        return num == r.num && den == r.den;
    }
    @Override public int hashCode() { return Objects.hash(num, den); }
    @Override public String toString() { return den == 1 ? Long.toString(num) : num + "/" + den; }
}
