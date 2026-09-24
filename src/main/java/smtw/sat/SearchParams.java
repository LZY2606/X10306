package smtw.sat;

/**
 * Parameters of the local bounded search. Every derived verdict records a fingerprint of these,
 * so re-running with changed parameters creates a new candidate instead of overwriting history.
 */
public record SearchParams(int intBound, long seed, boolean allowRealEnumeration, long timeEpochMs) {

    public static SearchParams DEFAULT = new SearchParams(3, 20260925L, false, 0L);

    public String fingerprint() {
        return "B=" + intBound + "|seed=" + seed + "|realEnum=" + allowRealEnumeration + "|t=" + timeEpochMs;
    }

    public SearchParams withIntBound(int b) {
        return new SearchParams(b, seed, allowRealEnumeration, timeEpochMs);
    }

    public SearchParams atTime(long ms) {
        return new SearchParams(intBound, seed, allowRealEnumeration, ms);
    }
}
