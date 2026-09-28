package witness.util;

import java.util.Map;
import java.util.TreeMap;

/** Algorithm/parameter version tracking for every derived value. */
public final class Fingerprints {
    public static final String REPLAY_ALGO = "smt-scope-replay";
    public static final String REPLAY_ALGO_VERSION = "1.0.0";
    public static final String MODEL_ALGO = "exact-model-eval";
    public static final String MODEL_ALGO_VERSION = "1.0.0";
    public static final String CORE_ALGO = "unsat-core-witness";
    public static final String CORE_ALGO_VERSION = "1.0.0";
    public static final String DECIDE_ALGO = "finite-simplex-decide";
    public static final String DECIDE_ALGO_VERSION = "1.0.0";

    private Fingerprints() {
    }

    public static String fingerprint(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return Hashing.sha256Hex(sb.toString()).substring(0, 16);
    }
}
