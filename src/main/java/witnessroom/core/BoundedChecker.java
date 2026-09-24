package witnessroom.core;

import witnessroom.smt.EvalNode;
import witnessroom.smt.Evaluator;
import witnessroom.smt.Model;
import witnessroom.smt.SExpr;
import witnessroom.smt.Value;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Local finite-domain satisfiability checker. Enumerates Int variables in
 * [-bound, bound] and Bool variables over {false,true} with a deterministic
 * seed. Results are only claims about the bounded space and always carry
 * the parameter fingerprint.
 */
public final class BoundedChecker {

    public static final String ALGORITHM = "bounded-enum";
    public static final String VERSION = "1.0.0";

    public record Params(int bound, long maxSpace, long seed) {
        public String canonical() {
            return "{\"algorithm\":\"" + ALGORITHM + "\",\"version\":\"" + VERSION
                    + "\",\"bound\":" + bound + ",\"maxSpace\":" + maxSpace + ",\"seed\":" + seed + "}";
        }
        public String fingerprint() { return sha256(canonical()); }
    }

    public enum Outcome { UNSAT_WITHIN_BOUND, SAT, UNKNOWN, UNSUPPORTED }

    public record CheckResult(Outcome outcome, Map<String, String> witness,
                              String reason, long explored, Params params) {}

    public static CheckResult check(List<SExpr> terms, Map<String, String> declarations, Params params) {
        List<String> intVars = new ArrayList<>();
        List<String> boolVars = new ArrayList<>();
        for (Map.Entry<String, String> d : new TreeMap<>(declarations).entrySet()) {
            switch (d.getValue()) {
                case "Int" -> intVars.add(d.getKey());
                case "Bool" -> boolVars.add(d.getKey());
                default -> { /* arrays / unsupported sorts stay unassigned */ }
            }
        }
        long space = 1;
        long width = 2L * params.bound() + 1;
        for (int i = 0; i < intVars.size(); i++) {
            space *= width;
            if (space > params.maxSpace())
                return new CheckResult(Outcome.UNKNOWN, null,
                        "enumeration space exceeds maxSpace=" + params.maxSpace(), 0, params);
        }
        for (int i = 0; i < boolVars.size(); i++) {
            space *= 2;
            if (space > params.maxSpace())
                return new CheckResult(Outcome.UNKNOWN, null,
                        "enumeration space exceeds maxSpace=" + params.maxSpace(), 0, params);
        }
        // deterministic order shuffling driven by the fixed seed
        List<long[]> points = new ArrayList<>();
        long combos = space;
        Random rng = new Random(params.seed());
        List<Long> order = new ArrayList<>();
        for (long i = 0; i < combos; i++) order.add(i);
        java.util.Collections.shuffle(order, rng);

        boolean sawUnknown = false;
        String unknownReason = null;
        long explored = 0;
        for (long code : order) {
            Map<String, Value> assignment = new LinkedHashMap<>();
            long rest = code;
            for (String v : intVars) {
                long digit = rest % width;
                rest /= width;
                assignment.put(v, new Value.IntV(BigInteger.valueOf(digit - params.bound())));
            }
            for (String v : boolVars) {
                long digit = rest % 2;
                rest /= 2;
                assignment.put(v, new Value.BoolV(digit == 1));
            }
            explored++;
            Evaluator ev = new Evaluator(new Model()).withAssignment(assignment);
            boolean allTrue = true;
            boolean anyUnsupported = false;
            boolean anyUnknown = false;
            String reason = null;
            for (SExpr t : terms) {
                EvalNode n = ev.eval(t);
                if (n.status == EvalNode.Status.VALUE && n.value instanceof Value.BoolV bv && bv.v()) continue;
                allTrue = false;
                if (n.status == EvalNode.Status.UNSUPPORTED) { anyUnsupported = true; reason = n.reason; }
                else if (n.status == EvalNode.Status.UNKNOWN) { anyUnknown = true; reason = n.reason; }
                break;
            }
            if (allTrue) {
                Map<String, String> witness = new LinkedHashMap<>();
                assignment.forEach((k, v) -> witness.put(k, Value.render(v)));
                return new CheckResult(Outcome.SAT, witness, null, explored, params);
            }
            if (anyUnsupported)
                return new CheckResult(Outcome.UNSUPPORTED, null, reason, explored, params);
            if (anyUnknown) { sawUnknown = true; unknownReason = reason; }
        }
        if (sawUnknown)
            return new CheckResult(Outcome.UNKNOWN, null, unknownReason, explored, params);
        return new CheckResult(Outcome.UNSAT_WITHIN_BOUND, null, null, explored, params);
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
