package smtw.smt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Declaration of the LOCAL finite semantic subset. Everything outside it must be reported as
 * unsupported rather than silently interpreted. The subset intentionally covers:
 * QF_UF, QF_LIA, QF_LRA, QF_UFLIA, QF_UFLRA, QF_ALIA (array select/store + const/default).
 */
public final class Theories {

    private Theories() {}

    public static final Set<String> SUPPORTED_LOGICS = Set.of(
            "QF_UF", "QF_LIA", "QF_LRA", "QF_UFLIA", "QF_UFLRA", "QF_ALIA", "QF_AUFLIA");

    /** Function symbols understood by the local evaluator. */
    public static final Set<String> SUPPORTED_FUNS = Set.of(
            "and", "or", "not", "=>", "xor", "=", "distinct", "ite", "true", "false",
            "+", "-", "*", "/", "div", "mod", "abs", "<", "<=", ">", ">=",
            "select", "store", "const");

    /** Constructs that, when seen inside a term, mark local verification as unsupported. */
    public static final Set<String> KNOWN_UNSUPPORTED_FUNS = Set.of(
            // bitvectors
            "concat", "extract", "bvnot", "bvand", "bvor", "bvneg", "bvadd", "bvmul",
            "bvudiv", "bvurem", "bvshl", "bvlshr", "bvult", "bvule", "bvugt", "bvuge",
            "bvsdiv", "bvsrem", "bvslt", "bvsle", "bvsgt", "bvsge", "bv2nat", "int2bv",
            // floating point
            "fp.abs", "fp.add", "fp.eq", "fp.lt", "fp.neg",
            // non-linear / extended
            "^", "sin", "cos", "tan", "exp", "log", "sqrt",
            "str.++", "str.len", "str.contains");

    /** Result of asking whether a logic declaration is locally supported. */
    public record LogicSupport(boolean supported, List<String> reasons, Set<String> theories) {
        public static LogicSupport yes(Set<String> th) {
            return new LogicSupport(true, List.of(), th);
        }
        public static LogicSupport no(List<String> reasons, Set<String> th) {
            return new LogicSupport(false, reasons, th);
        }
    }

    public static LogicSupport assessLogic(String logicName) {
        if (logicName == null || logicName.isBlank()) {
            return LogicSupport.yes(Set.of());
        }
        String n = logicName.trim().toUpperCase();
        if (SUPPORTED_LOGICS.contains(n)) {
            return LogicSupport.yes(theoriesOf(n));
        }
        Set<String> reasons = new LinkedHashSet<>();
        if (n.contains("BV")) reasons.add("bitvector theory (BV) has no local semantics");
        if (n.contains("FP")) reasons.add("floating point theory (FP) has no local semantics");
        if (n.contains("NIA")) reasons.add("non-linear integer arithmetic (NIA) is outside the local subset");
        if (n.contains("NRA")) reasons.add("non-linear real arithmetic (NRA) is outside the local subset");
        if (n.startsWith("AUF") || n.startsWith("UF")) {
            // still treat unknown extension as unsupported
        }
        if (reasons.isEmpty()) reasons.add("logic " + logicName + " is not part of the local finite subset");
        return LogicSupport.no(List.copyOf(reasons), theoriesOf(n));
    }

    static Set<String> theoriesOf(String n) {
        Set<String> th = new LinkedHashSet<>();
        if (n.contains("A")) th.add("ArraysEx");
        if (n.contains("UF")) th.add("UninterpretedFunctions");
        if (n.contains("LIA")) th.add("LinearIntArith");
        if (n.contains("LRA")) th.add("LinearRealArith");
        if (n.contains("BV")) th.add("Bitvectors(unsupported)");
        if (n.contains("FP")) th.add("FloatingPoint(unsupported)");
        return th.isEmpty() ? Set.of("Core") : th;
    }

    /** Walk a term and report the first unsupported construct found, or null. */
    public static String firstUnsupportedConstruct(Sexpr term) {
        if (term instanceof Sexpr.Atom) return null;
        Sexpr.List l = (Sexpr.List) term;
        if (!l.items().isEmpty() && l.items().get(0) instanceof Sexpr.Atom head) {
            String h = head.token();
            if ("forall".equals(h) || "exists".equals(h)) {
                return "quantifier '" + h + "' is outside the quantifier-free local subset";
            }
            if (KNOWN_UNSUPPORTED_FUNS.contains(h)) {
                return "symbol '" + h + "' is outside the locally supported theory subset";
            }
            if (h.startsWith("bv") || h.startsWith("fp.")) {
                return "symbol '" + h + "' is outside the locally supported theory subset";
            }
        }
        for (Sexpr child : l.items()) {
            String r = firstUnsupportedConstruct(child);
            if (r != null) return r;
        }
        return null;
    }
}
