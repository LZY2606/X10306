package smtwitness.smtlib;

import java.util.ArrayList;
import java.util.List;

/**
 * Term graph used by the local verifier. Each node keeps its original
 * representation (raw SMT-LIB text) and a stable structural hash.
 */
public final class Term {
    private final String op;
    private final List<Term> children;
    private final Sort sort;
    private final String raw;
    private String hash;

    private Term(String op, List<Term> children, Sort sort, String raw) {
        this.op = op;
        this.children = List.copyOf(children);
        this.sort = sort;
        this.raw = raw;
    }

    public static Term symbol(String name, Sort sort) {
        return new Term(name, List.of(), sort, name);
    }

    public static Term app(String op, List<Term> children, Sort sort, String raw) {
        return new Term(op, children, sort, raw);
    }

    public String op() {
        return op;
    }

    public List<Term> children() {
        return children;
    }

    public Sort sort() {
        return sort;
    }

    public String raw() {
        return raw;
    }

    /** Content-addressed id used inside evaluation trees. */
    public String stableHash() {
        if (hash == null) {
            StringBuilder sb = new StringBuilder("t:").append(op);
            for (Term child : children) {
                sb.append('|').append(child.stableHash());
            }
            sb.append(':').append(sort == null ? "?" : sort.basis());
            hash = DigestUtil.shortDigest(sb.toString());
        }
        return hash;
    }

    public List<String> freeSymbolNames() {
        List<String> out = new ArrayList<>();
        collectFree(out);
        return out;
    }

    private void collectFree(List<String> out) {
        if (children.isEmpty() && !op.equals("true") && !op.equals("false")) {
            if (!out.contains(op)) {
                out.add(op);
            }
            return;
        }
        for (Term child : children) {
            child.collectFree(out);
        }
    }
}
