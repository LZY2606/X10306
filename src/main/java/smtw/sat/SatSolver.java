package smtw.sat;

import smtw.model.Val;
import smtw.smt.Sexpr;
import smtw.smt.SymbolDecl;
import smtw.smt.SymbolTable;
import smtw.smt.Sort;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Local bounded satisfiability search over the finite subset:
 *  - Bool and Int (bounded interval [-B,B]) variables are enumerated exhaustively;
 *  - Real variables, arrays-as-search-variables and unsupported constructs force UNKNOWN;
 *  - enumeration order is derived from a fixed seed, making all tests deterministic.
 * The solver never claims SAT/UNSAT when the search could not cover the domain.
 */
public final class SatSolver {

    private final SymbolTable symbols;
    private final SearchParams params;

    public SatSolver(SymbolTable symbols, SearchParams params) {
        this.symbols = symbols;
        this.params = params;
    }

    public SearchResult search(List<Sexpr> constraints) {
        Map<String, Sort> domains = new LinkedHashMap<>();
        List<String> blockers = new ArrayList<>();
        for (Sexpr c : constraints) {
            String u = smtw.smt.Theories.firstUnsupportedConstruct(c);
            if (u != null) blockers.add(u);
            for (String v : FreeVarFinder.find(c)) {
                SymbolDecl d = symbols.symbols().get(v);
                if (d == null) {
                    blockers.add("free symbol '" + v + "' is not declared in the transcript");
                } else {
                    Sort s = d.resultSort();
                    if (s == Sort.Prim.REAL && !params.allowRealEnumeration()) {
                        blockers.add("real symbol '" + v + "' is not covered by the finite local search");
                    } else if (s instanceof Sort.App) {
                        blockers.add("array symbol '" + v + "' cannot be used as a search variable locally");
                    } else if (s instanceof Sort.Decl) {
                        blockers.add("uninterpreted sort symbol '" + v + "' has no finite domain locally");
                    } else {
                        domains.putIfAbsent(v, s);
                    }
                }
            }
        }
        if (!blockers.isEmpty()) {
            return SearchResult.unknown(0, blockers, false);
        }
        List<Map<String, Val>> envs = enumerate(new ArrayList<>(domains.keySet()));
        int tried = 0;
        for (Map<String, Val> env : envs) {
            tried++;
            boolean all = true;
            for (Sexpr c : constraints) {
                Val v = AssignmentEval.eval(c, env);
                if (v instanceof Val.UnsupportedV u) {
                    return SearchResult.unknown(tried, List.of(u.reason()), false);
                }
                if (!(v instanceof Val.BoolV b)) {
                    return SearchResult.unknown(tried,
                            List.of("constraint did not evaluate to Bool under assignment: " + v), false);
                }
                if (!b.b()) { all = false; break; }
            }
            if (all) {
                return SearchResult.sat(env, renderModel(env), tried);
            }
        }
        List<String> why = new ArrayList<>();
        why.add("exhausted the bounded finite domain: " + domains.size()
                + " variable(s), int bound B=" + params.intBound() + ", assignments=" + tried);
        boolean bounded = domains.values().stream().anyMatch(s -> s == Sort.Prim.INT);
        return bounded
                ? new SearchResult(SearchResult.Verdict.UNSAT, Map.of(), null,
                    addBoundedNote(why), tried, true)
                : SearchResult.unsat(tried, why);
    }

    private List<String> addBoundedNote(List<String> why) {
        List<String> out = new ArrayList<>(why);
        out.add("note: UNSAT is relative to the bounded interval [-B,B]; unbounded integers stay a "
                + "bounded-only result unless every constraint forces variables into the interval");
        return out;
    }

    private List<Map<String, Val>> enumerate(List<String> vars) {
        Random rnd = new Random(params.seed());
        List<List<Val>> domains = new ArrayList<>();
        for (String v : vars) {
            Sort s = symbols.symbols().get(v).resultSort();
            domains.add(s == Sort.Prim.BOOL
                    ? List.of(Val.BoolV.TRUE, Val.BoolV.FALSE)
                    : intDomain(params.intBound(), rnd));
        }
        List<Map<String, Val>> out = new ArrayList<>();
        long[] idx = new long[vars.size()];
        long total = 1;
        for (List<Val> d : domains) total *= d.size();
        for (long n = 0; n < total; n++) {
            long x = n;
            Map<String, Val> env = new LinkedHashMap<>();
            for (int i = 0; i < vars.size(); i++) {
                List<Val> d = domains.get(i);
                env.put(vars.get(i), d.get((int) (x % d.size())));
                x /= d.size();
            }
            out.add(env);
        }
        return out;
    }

    private List<Val> intDomain(int b, Random rnd) {
        List<Val> vs = new ArrayList<>();
        for (int i = -b; i <= b; i++) vs.add(new Val.IntV(BigInteger.valueOf(i)));
        // fixed-seed permutation keeps determinism while avoiding canonical order bias
        java.util.Collections.shuffle(vs, rnd);
        return List.copyOf(vs);
    }

    private String renderModel(Map<String, Val> env) {
        StringBuilder sb = new StringBuilder("(\n");
        for (Map.Entry<String, Val> e : env.entrySet()) {
            Sort s = symbols.symbols().get(e.getKey()).resultSort();
            String sortName = s == Sort.Prim.BOOL ? "Bool" : s == Sort.Prim.REAL ? "Real" : "Int";
            sb.append("  (define-fun ").append(e.getKey())
              .append(" () ").append(sortName).append(' ');
            Val v = e.getValue();
            if (v instanceof Val.BoolV bv) sb.append(bv.b());
            else sb.append(v);
            sb.append(")\n");
        }
        return sb.append(")").toString();
    }
}
