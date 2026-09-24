package witnessroom.smt;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stack-scoped evaluation environment. pushLevel/popLevel mirror SMT-LIB
 * assertion stack levels for declarations and let bindings are frame-local.
 */
public final class Environment {

    private final Map<String, Sort> sorts = new LinkedHashMap<>();
    private final Map<String, FuncSig> funcs = new LinkedHashMap<>();
    private final java.util.Deque<Map<String, Value>> letStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<Map<String, Sort>> declStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<Map<String, FuncSig>> funcStack = new java.util.ArrayDeque<>();

    public Environment() {
        letStack.push(new LinkedHashMap<>());
        declStack.push(new LinkedHashMap<>());
        funcStack.push(new LinkedHashMap<>());
    }

    public void pushLevel() {
        letStack.push(new LinkedHashMap<>(letStack.peek()));
        declStack.push(new LinkedHashMap<>());
        funcStack.push(new LinkedHashMap<>());
    }

    public void popLevel() {
        if (letStack.size() <= 1) throw new EvalException("cannot pop global level");
        for (String k : declStack.pop().keySet()) sorts.remove(k);
        for (String k : funcStack.pop().keySet()) funcs.remove(k);
        letStack.pop();
    }

    public int levels() { return declStack.size(); }

    public void declareConst(String name, Sort sort) {
        sorts.put(name, sort);
        declStack.peek().put(name, sort);
    }

    public Sort sortOf(String name) { return sorts.get(name); }

    public void declareFunc(FuncSig sig) {
        funcs.put(sig.name(), sig);
        funcStack.peek().put(sig.name(), sig);
    }

    public FuncSig funcOf(String name) { return funcs.get(name); }

    public void bindLet(String name, Value v) { letStack.peek().put(name, v); }
    public Value lookupLet(String name) { return letStack.peek().get(name); }

    public List<String> declaredNamesView() { return List.copyOf(sorts.keySet()); }
}
