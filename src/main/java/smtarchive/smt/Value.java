package smtarchive.smt;

import java.util.LinkedHashMap;
import java.util.Map;

/** Values produced by the local fragment evaluator. */
public sealed interface Value permits Value.Bool, Value.Num, Value.Array, Value.Uninterpreted, Value.UnsupportedValue {
    record Bool(boolean b) implements Value {}
    record Num(Rational r, boolean isInt) implements Value {}
    record Array(Map<Value, Value> stores, Value defaultValue, Sort indexSort, Sort elemSort) implements Value {
        public Array withDefault(Value d) { return new Array(stores, d, indexSort, elemSort); }
    }
    record Uninterpreted(String name, Sort sort) implements Value {}
    record UnsupportedValue(String reason, String raw) implements Value {}

    static Value fromTermConstant(Term t) {
        return switch (t) {
            case Term.Num n -> new Num(n.value(), n.isInt());
            case Term.BoolLit b -> new Bool(b.value());
            default -> new UnsupportedValue("not a literal", t.toString());
        };
    }

    default String render() {
        return switch (this) {
            case Bool b -> Boolean.toString(b.b());
            case Num n -> n.r().toString() + (n.isInt() ? "" : "");
            case Array a -> "(array (default " + a.defaultValue().render() + ") stores=" + a.stores().size() + ")";
            case Uninterpreted u -> u.name();
            case UnsupportedValue u -> "UNSUPPORTED:" + u.reason();
        };
    }
}
