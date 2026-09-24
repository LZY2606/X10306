package org.witness.smt.model;

import java.util.List;
import java.util.Map;

/** Values produced by the local evaluator for the supported semantic fragment. */
public sealed interface Value {
    record Bool(boolean b) implements Value {}
    record Int(Rat n) implements Value {}
    record Str(String s) implements Value {}
    /** Abstract element of an uninterpreted sort, e.g. (as const.something U). */
    record Element(String name, String sort) implements Value {}
    record Arr(Rat domainSize, Rat defaultVal, Map<Rat, Rat> store) implements Value {
        public Rat select(Rat index) {
            Rat in = store.get(index);
            return in != null ? in : defaultVal;
        }
    }
    record App(String fname, List<Value> args, Value result) implements Value {}

    static Bool ofBool(boolean b) { return new Bool(b); }
    static Int ofInt(long n) { return new Int(Rat.of(n)); }
}
