package chamber.engine;

import chamber.util.Ids;

import java.util.List;

/** Parameters of the local finite-domain re-check. Every derived result carries
 *  the fingerprint of the parameter set that produced it. */
public record SolverParams(
        List<Long> domain,
        int maxCombinations,
        int maxReductionRounds,
        long seed,
        String algoVersion) {

    public static final String ALGO_VERSION = "finite-enum/1.2";

    public static SolverParams defaults() {
        return new SolverParams(List.of(-2L, -1L, 0L, 1L, 2L), 100_000, 8, 17L, ALGO_VERSION);
    }

    public String fingerprint() {
        return Ids.of("fp", algoVersion, domain.toString(),
                String.valueOf(maxCombinations), String.valueOf(maxReductionRounds),
                String.valueOf(seed));
    }
}
