package org.witness.smt.domain;

import java.util.Objects;
import org.witness.smt.SExpr;

/** Sorts in the supported local subset plus an explicit unsupported marker. */
public abstract sealed class Sort permits Sort.Bool, Sort.IntSort, Sort.RealSort, Sort.ArraySort, Sort.Unsupported {

    public static final Bool BOOL = new Bool();
    public static final IntSort INT = new IntSort();
    public static final RealSort REAL = new RealSort();

    public abstract String name();

    public static final class Bool extends Sort {
        @Override public String name() { return "Bool"; }
    }

    public static final class IntSort extends Sort {
        @Override public String name() { return "Int"; }
    }

    public static final class RealSort extends Sort {
        @Override public String name() { return "Real"; }
    }

    public static final class ArraySort extends Sort {
        public final Sort index;
        public final Sort element;

        public ArraySort(Sort index, Sort element) {
            this.index = index;
            this.element = element;
        }

        @Override public String name() {
            return "(Array " + index.name() + " " + element.name() + ")";
        }
    }

    public static final class Unsupported extends Sort {
        public final String raw;
        public final String reason;

        public Unsupported(String raw, String reason) {
            this.raw = raw;
            this.reason = reason;
        }

        @Override public String name() {
            return raw;
        }
    }

    public static Sort fromExpr(SExpr expr) {
        if (expr.isAtom()) {
            return switch (expr.atom()) {
                case "Bool" -> BOOL;
                case "Int" -> INT;
                case "Real" -> REAL;
                default -> new Unsupported(expr.atom(), "uninterpreted or unsupported sort");
            };
        }
        if ("Array".equals(expr.head()) && expr.arity() == 2) {
            Sort i = fromExpr(expr.arg(0));
            Sort e = fromExpr(expr.arg(1));
            return new ArraySort(i, e);
        }
        return new Unsupported(expr.toString(), "compound unsupported sort");
    }

    public boolean isSupported() {
        if (this instanceof Unsupported) {
            return false;
        }
        if (this instanceof ArraySort a) {
            return a.index.isSupported() && a.element.isSupported();
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Sort other)) {
            return false;
        }
        return this.name().equals(other.name());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name());
    }
}
