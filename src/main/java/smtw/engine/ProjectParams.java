package smtw.engine;

import smtw.sat.SearchParams;

/** A named revision of local-search parameters; fingerprints invalidate dependent derived results. */
public record ParamVersion(String id, int version, SearchParams params,
                          String parentParamVersionId, String reason, long atMs) {
}
