package smtwitness.smt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One parsed SMT-LIB command; the original text is always retained. */
public record Command(
        int blockSeq,
        Kind kind,
        String raw,
        String logic,
        String symbol,
        SExpr sort,
        SExpr term,
        List<NamedAssert> namedAsserts,
        List<String> assumptions,
        List<ModelEntry> modelEntries,
        List<String> core,
        String status,
        String error,
        List<SExpr> rawForms
) {
    public enum Kind {
        SET_LOGIC, DECLARE_SORT, DEFINE_SORT, DECLARE_CONST, DECLARE_FUN, DEFINE_FUN,
        ASSERT, PUSH, POP, CHECK_SAT, CHECK_SAT_ASSUMING, GET_MODEL, GET_UNSAT_CORE,
        ECHO, SET_OPTION, OTHER
    }

    public Command {
        namedAsserts = namedAsserts == null ? List.of() : List.copyOf(namedAsserts);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        modelEntries = modelEntries == null ? List.of() : List.copyOf(modelEntries);
        core = core == null ? List.of() : List.copyOf(core);
        rawForms = rawForms == null ? List.of() : List.copyOf(rawForms);
    }

    /** One (label, formula) pair extracted from an assert containing ! :named. */
    public record NamedAssert(String label, SExpr formula) {}

    /** One define-fun / define-const inside a model response. */
    public record ModelEntry(String name, List<String> argNames, List<SExpr> argSorts, SExpr resultSort, SExpr body) {
        public boolean isConstant() { return argNames.isEmpty(); }
    }

    public static Optional<Kind> kindOf(String head) {
        return switch (head) {
            case "set-logic" -> Optional.of(Kind.SET_LOGIC);
            case "declare-sort" -> Optional.of(Kind.DECLARE_SORT);
            case "define-sort" -> Optional.of(Kind.DEFINE_SORT);
            case "declare-const" -> Optional.of(Kind.DECLARE_CONST);
            case "declare-fun" -> Optional.of(Kind.DECLARE_FUN);
            case "define-fun" -> Optional.of(Kind.DEFINE_FUN);
            case "assert" -> Optional.of(Kind.ASSERT);
            case "push" -> Optional.of(Kind.PUSH);
            case "pop" -> Optional.of(Kind.POP);
            case "check-sat" -> Optional.of(Kind.CHECK_SAT);
            case "check-sat-assuming" -> Optional.of(Kind.CHECK_SAT_ASSUMING);
            case "get-model" -> Optional.of(Kind.GET_MODEL);
            case "get-unsat-core" -> Optional.of(Kind.GET_UNSAT_CORE);
            case "echo" -> Optional.of(Kind.ECHO);
            case "set-option" -> Optional.of(Kind.SET_OPTION);
            default -> Optional.empty();
        };
    }

    /** Extract ! :named L annotations from an assert term. */
    public static List<NamedAssert> extractNames(String termRaw) {
        List<NamedAssert> out = new ArrayList<>();
        try {
            SExpr e = SExpr.parse(termRaw);
            collectNamed(e, out);
        } catch (RuntimeException ignored) {
            // malformed terms keep no structured names; raw text stays in the block
        }
        return out;
    }

    private static void collectNamed(SExpr e, List<NamedAssert> out) {
        if (e instanceof SExpr.SList l && !l.items().isEmpty()) {
            if ("!".equals(l.headSym()) && l.items().size() >= 2) {
                SExpr formula = l.items().get(1);
                String label = null;
                for (int i = 2; i + 1 < l.items().size(); i++) {
                    if (l.items().get(i) instanceof Atom a && ":named".equals(a.text())
                            && l.items().get(i + 1) instanceof Atom lab) {
                        label = stripBars(lab.text());
                    }
                }
                if (label != null) out.add(new NamedAssert(label, formula));
                collectNamed(formula, out);
            }
            for (SExpr child : l.items()) collectNamed(child, out);
        }
    }

    private static String stripBars(String s) {
        return s.length() >= 2 && s.startsWith("|") && s.endsWith("|") ? s.substring(1, s.length() - 1) : s;
    }

    public static Builder builder(int blockSeq, String raw) { return new Builder(blockSeq, raw); }

    public static final class Builder {
        private final int blockSeq;
        private final String raw;
        private Kind kind = Kind.OTHER;
        private String logic;
        private String symbol;
        private SExpr sort;
        private SExpr term;
        private List<NamedAssert> namedAsserts = new ArrayList<>();
        private List<String> assumptions = new ArrayList<>();
        private List<ModelEntry> modelEntries = new ArrayList<>();
        private List<String> core = new ArrayList<>();
        private String status;
        private String error;
        private List<SExpr> rawForms = new ArrayList<>();

        private Builder(int blockSeq, String raw) { this.blockSeq = blockSeq; this.raw = raw; }
        public Builder kind(Kind k) { this.kind = k; return this; }
        public Builder logic(String v) { this.logic = v; return this; }
        public Builder symbol(String v) { this.symbol = v; return this; }
        public Builder sort(SExpr v) { this.sort = v; return this; }
        public Builder term(SExpr v) { this.term = v; return this; }
        public Builder namedAsserts(List<NamedAssert> v) { this.namedAsserts = new ArrayList<>(v); return this; }
        public Builder assumptions(List<String> v) { this.assumptions = new ArrayList<>(v); return this; }
        public Builder modelEntries(List<ModelEntry> v) { this.modelEntries = new ArrayList<>(v); return this; }
        public Builder core(List<String> v) { this.core = new ArrayList<>(v); return this; }
        public Builder status(String v) { this.status = v; return this; }
        public Builder error(String v) { this.error = v; return this; }
        public Builder rawForms(List<SExpr> v) { this.rawForms = new ArrayList<>(v); return this; }
        public Command build() {
            return new Command(blockSeq, kind, raw, logic, symbol, sort, term, namedAsserts,
                    assumptions, modelEntries, core, status, error, rawForms);
        }
    }
}

