package smtw.sat;

import smtw.model.Val;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Outcome of the local bounded search. UNKNOWN is a first-class verdict, never reported as sat/unsat. */
public record SearchResult(Verdict verdict, Map<String, Val> witness, String modelText,
                           List<String> reasons, int assignmentsTried, boolean boundedOnly) {

    public enum Verdict { SAT, UNSAT, UNKNOWN }

    public SearchResult {
        witness = witness == null ? Map.of() : Map.copyOf(witness);
        reasons = List.copyOf(reasons);
    }

    public static SearchResult sat(Map<String, Val> w, String modelText, int tried) {
        return new SearchResult(Verdict.SAT, new LinkedHashMap<>(w), modelText,
                List.of("found a satisfying assignment inside the bounded local domain"), tried, false);
    }

    public static SearchResult unsat(int tried, List<String> reasons) {
        return new SearchResult(Verdict.UNSAT, Map.of(), null, reasons, tried, false);
    }

    public static SearchResult unknown(int tried, List<String> reasons, boolean boundedOnly) {
        return new SearchResult(Verdict.UNKNOWN, Map.of(), null, reasons, tried, boundedOnly);
    }
}
