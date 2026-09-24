package smtwitness.engine;

import smtwitness.smt.SExpr;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Values in the local finite semantic subset. Missing arrays are {@link Arr} maps with gaps. */
public sealed interface Val {
    BoolVal TRUE = new BoolVal(true);
    BoolVal FALSE = new BoolVal(false);

    record BoolVal(boolean b) implements Val {
        public static BoolVal of(boolean b) { return b ? TRUE : FALSE; }
    }
    record IntVal(long n) implements Val {}
    record ArrVal(Map<Long, Val> stores, Val defaultValue) implements Val {
        public ArrVal {
            stores = Map.copyOf(new TreeMap<>(stores));
        }
        public Val get(long idx) {
            Val v = stores.get(idx);
            return v != null ? v : defaultValue;
        }
        public ArrVal with(long idx, Val v) {
            Map<Long, Val> m = new TreeMap<>(stores);
            m.put(idx, v);
            return new ArrVal(m, defaultValue);
        }
    }
    record UnknownVal(String reason) implements Val {}

    static Val fromBool(boolean b) { return BoolVal.of(b); }

    /** Kleene conjunction: null components mean unknown. Returns null when undecided. */
    static Boolean kleeneAnd(List<Boolean> bs) {
        boolean anyUnknown = false;
        for (Boolean b : bs) {
            if (b == null) anyUnknown = true;
            else if (!b) return false;
        }
        return anyUnknown ? null : true;
    }
}
