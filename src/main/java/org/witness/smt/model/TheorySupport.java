package org.witness.smt.model;

import org.witness.smt.parse.SExpr;

import java.util.HashSet;
import java.util.Set;

/**
 * Declares the local semantic fragment.  Anything outside it is flagged
 * explicitly rather than silently evaluated wrong: bit-vectors, reals,
 * floating point, strings, quantifiers, recursive functions, non-linear
 * multiplication on terms, etc.
 */
public final class TheorySupport {
    private TheorySupport() {}

    public static final Set<String> SUPPORTED_LOGIC = Set.of(
            "WITNESS_ROOM", "QF_UFLIA", "QF_LIA", "QF_UF", "", "ALL");

    public static final Set<String> BV_TOKENS = Set.of(
            "BitVec", "bv", "bvadd", "bvmul", "bvsub", "bvand", "bvor", "bvnot",
            "concat", "extract", "bvslt", "bvult", "bvsle", "bvule", "bvsgt",
            "bvugt", "bvsge", "bvuge", "bv2nat", "int2bv");

    /** Walk a term and return the first unsupported feature, or null. */
    public static String firstUnsupported(SExpr term) {
        if (term instanceof SExpr.Atom a) {
            String t = a.token();
            if (t.startsWith("#b") || t.startsWith("#x")) return "bitvector-literal";
            if (t.contains("/")) return null; // rational literal handled by arithmetic fragment
            return null;
        }
        SExpr.List l = (SExpr.List) term;
        String head = l.headName();
        if (head.equals("forall") || head.equals("exists") || head.equals("lambda"))
            return head;
        if (head.equals("_")) {
            String op = l.items().get(1) instanceof SExpr.Atom a ? a.token() : "";
            if (op.startsWith("extract") || op.startsWith("bv")) return "bitvector-operator";
            for (SExpr x : l.items()) {
                if (x instanceof SExpr.Atom a && (a.token().startsWith("BitVec"))) return "bitvector-sort";
            }
        }
        if (head.equals("as")) {
            SExpr sort = l.items().get(1);
            String s = sort.raw();
            if (s.contains("BitVec")) return "bitvector-sort";
            if (s.equals("Real") || s.startsWith("Real")) return "real-sort";
            if (s.startsWith("String") || s.contains("RegExp") || s.startsWith("FloatingPoint"))
                return "unsupported-sort:" + s;
        }
        if (head.equals("declare-fun") || head.equals("define-fun-rec")) return head;
        if (BV_TOKENS.contains(head)) return "bitvector-operator";
        if (Set.of("/", "to_real", "is_int", "^").contains(head)) return "real-arithmetic";
        if (Set.of("str.++", "str.len", "str.contains", "str.at", "str.replace",
                "re.++", "str.in_re").contains(head)) return "string-theory";
        for (SExpr child : l.items()) {
            String u = firstUnsupported(child);
            if (u != null) return u;
        }
        return null;
    }
}
