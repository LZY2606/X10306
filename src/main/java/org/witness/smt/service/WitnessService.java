package org.witness.smt.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.witness.smt.replay.Transcript;
import org.witness.smt.service.RevisionAnalysis.Analysis;
import org.witness.smt.service.RevisionAnalysis.RevisionKind;
import org.witness.smt.semantics.BoundedSolver;
import org.witness.smt.store.Clock;
import org.witness.smt.store.Ids;
import org.witness.smt.store.ProjectStore;

/**
 * Application service: immutable imports with parent links, derived results
 * carrying algorithm version / parameter fingerprint / direct sources,
 * human rulings, and export/import that preserves identity and order.
 */
public final class WitnessService implements AutoCloseable {

    private final ProjectStore store;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final Path dbPath;

    public WitnessService(Path dbPath, Clock clock) {
        this.dbPath = dbPath;
        this.store = new ProjectStore(dbPath, clock);
    }

    public ProjectStore store() {
        return store;
    }

    public static String createProjectId(String name) {
        return "prj_" + Ids.sha12("project", name);
    }

    public void ensureProject(String projectId, String name) {
        if (!store.projectExists(projectId)) {
            store.createProject(projectId, name);
        }
    }

    /** Imports a transcript. The raw text is immutable once submitted. */
    public RevisionRef importTranscript(String projectId, String name, String rawText,
                                        String kind, String label, String parentRevisionId) {
        ensureProject(projectId, name);
        String importId = Ids.importId(rawText);
        int version;
        if (parentRevisionId == null) {
            if (store.projectExists(projectId) && !store.imports(projectId).isEmpty()) {
                throw new IllegalArgumentException("initial import already exists for project " + projectId);
            }
            version = 1;
        } else {
            version = store.nextImportVersion(projectId);
        }
        store.addImport(importId, projectId, version, parentRevisionId, kind, label, rawText);
        JsonObject payload = new JsonObject();
        payload.addProperty("importId", importId);
        payload.addProperty("version", version);
        payload.addProperty("parent", parentRevisionId);
        payload.addProperty("kind", kind);
        payload.addProperty("label", label);
        payload.addProperty("rawSha", Ids.sha12(rawText));
        String eventId = store.addEvent(projectId, importId,
                RevisionKind.INITIAL.name().equals(kind) ? "import-initial" : "import-revision",
                        gson.toJson(payload));
        deriveAll(projectId, importId, BoundedSolver.Params.defaults(), eventId);
        return new RevisionRef(importId, version, parentRevisionId);
    }

    public record RevisionRef(String importId, int version, String parentId) {
    }

    /** Re-runs the analysis with changed bounded-search parameters (a parameter revision). */
    public String reviseParameters(String projectId, String revisionId, BoundedSolver.Params params) {
        JsonObject payload = new JsonObject();
        payload.addProperty("revisionId", revisionId);
        payload.addProperty("paramFingerprint", params.fingerprint());
        payload.addProperty("intBound", params.intBound());
        payload.addProperty("realGrid", params.realGrid());
        payload.addProperty("maxCandidates", params.maxCandidates());
        String eventId = store.addEvent(projectId, revisionId, "param-revision", gson.toJson(payload));
        deriveAll(projectId, revisionId, params, eventId);
        return eventId;
    }

    private void deriveAll(String projectId, String revisionId, BoundedSolver.Params params, String causedByEvent) {
        String raw = rawTextOf(projectId, revisionId);
        Analysis analysis = RevisionAnalysis.analyze(revisionId, raw, params, RevisionKind.INITIAL);
        JsonObject batch = new JsonObject();
        batch.addProperty("causedBy", causedByEvent);
        batch.addProperty("algorithmVersion", store.algorithmVersion);
        batch.addProperty("paramFingerprint", params.fingerprint());
        JsonArray ids = new JsonArray();

        for (var m : analysis.models().entrySet()) {
            var summary = m.getValue();
            String id = summary.derivedId();
            ids.add(id);
            JsonObject p = new JsonObject();
            p.add("summary", gson.toJsonTree(summary));
            store.addDerived(id, projectId, revisionId, m.getKey(), "model",
                    summary.status(), params.fingerprint(),
                    revisionId + "," + summary.evals().stream().findFirst()
                            .map(e -> e.assertionId()).orElse(""),
                    gson.toJson(p));
        }
        for (var c : analysis.cores().entrySet()) {
            var summary = c.getValue();
            String id = summary.derivedId();
            ids.add(id);
            JsonObject p = new JsonObject();
            p.add("summary", gson.toJsonTree(summary));
            List<String> src = new ArrayList<>();
            src.add(revisionId);
            src.addAll(summary.labels());
            store.addDerived(id, projectId, revisionId, c.getKey(), "core",
                    summary.status(), params.fingerprint(), String.join(",", src), gson.toJson(p));
        }
        batch.add("derivedIds", ids);
        store.addEvent(projectId, revisionId, "derived-batch", gson.toJson(batch));
    }

    private String rawTextOf(String projectId, String revisionId) {
        for (Object[] row : store.imports(projectId)) {
            if (revisionId.equals(row[0])) {
                return (String) row[5];
            }
        }
        throw new IllegalArgumentException("unknown revision " + revisionId);
    }

    public String rule(String projectId, String revisionId, String decision, String note,
                       String derivedId) {
        int ordinal = store.nextRulingOrdinal(projectId);
        JsonObject payload = new JsonObject();
        payload.addProperty("derivedId", derivedId);
        payload.addProperty("decision", decision);
        payload.addProperty("note", note);
        String eventId = store.addEvent(projectId, revisionId, "ruling", gson.toJson(payload));
        String rulingId = "rul_" + Ids.sha12("ruling", projectId, String.valueOf(ordinal), decision);
        store.addRuling(rulingId, projectId, derivedId, ordinal, decision, note, eventId);
        return rulingId;
    }

    public Analysis analysisFor(String projectId, String revisionId) {
        return analysisFor(projectId, revisionId, BoundedSolver.Params.defaults());
    }

    public Analysis analysisFor(String projectId, String revisionId, BoundedSolver.Params params) {
        return RevisionAnalysis.analyze(revisionId, rawTextOf(projectId, revisionId), params,
                RevisionKind.INITIAL);
    }

    /** Full JSON snapshot; identities and ordering are deterministic. */
    public String exportProject(String projectId) {
        JsonObject root = new JsonObject();
        root.addProperty("format", "solver-witness-room/1");
        root.addProperty("projectId", projectId);
        JsonArray projects = new JsonArray();
        for (String[] p : store.listProjects()) {
            if (p[0].equals(projectId)) {
                JsonObject o = new JsonObject();
                o.addProperty("id", p[0]);
                o.addProperty("name", p[1]);
                projects.add(o);
            }
        }
        root.add("projects", projects);
        root.add("imports", rows(store.imports(projectId),
                "id", "version", "parentId", "kind", "label", "rawText"));
        root.add("events", rows(store.events(projectId),
                "id", "ordinal", "revisionId", "type", "payload", "createdAt"));
        root.add("derived", rows(store.derived(projectId),
                "id", "revisionId", "checkSeq", "kind", "status", "algorithmVersion",
                "paramFingerprint", "sources", "payload"));
        root.add("rulings", rows(store.rulings(projectId),
                "id", "derivedId", "ordinal", "decision", "note", "eventId"));
        return gson.toJson(root);
    }

    private JsonArray rows(List<Object[]> rows, String... keys) {
        JsonArray arr = new JsonArray();
        for (Object[] r : rows) {
            JsonObject o = new JsonObject();
            for (int i = 0; i < keys.length; i++) {
                Object v = r[i];
                if (v == null) {
                    o.add(keys[i], null);
                } else if (v instanceof Number n) {
                    o.addProperty(keys[i], n);
                } else {
                    o.addProperty(keys[i], v.toString());
                }
            }
            arr.add(o);
        }
        return arr;
    }

    /** Imports an exported snapshot into a (possibly freshly created) database. */
    public void importProject(String json) {
        JsonObject root = gson.fromJson(json, JsonObject.class);
        if (!"solver-witness-room/1".equals(root.get("format").getAsString())) {
            throw new IllegalArgumentException("unsupported export format");
        }
        String projectId = root.get("projectId").getAsString();
        for (var e : root.getAsJsonArray("projects")) {
            JsonObject o = e.getAsJsonObject();
            if (!store.projectExists(o.get("id").getAsString())) {
                store.createProject(o.get("id").getAsString(), o.get("name").getAsString());
            }
        }
        for (var e : root.getAsJsonArray("imports")) {
            JsonObject o = e.getAsJsonObject();
            store.addImport(o.get("id").getAsString(), projectId, o.get("version").getAsInt(),
                    strOrNull(o, "parentId"), o.get("kind").getAsString(), o.get("label").getAsString(),
                    o.get("rawText").getAsString());
        }
        for (var e : root.getAsJsonArray("events")) {
            JsonObject o = e.getAsJsonObject();
            insertEvent(projectId, o);
        }
        for (var e : root.getAsJsonArray("derived")) {
            JsonObject o = e.getAsJsonObject();
            store.addDerived(o.get("id").getAsString(), projectId, o.get("revisionId").getAsString(),
                    o.get("checkSeq").getAsInt(), o.get("kind").getAsString(),
                    o.get("status").getAsString(), o.get("paramFingerprint").getAsString(),
                    o.get("sources").getAsString(), o.get("payload").getAsString());
        }
        for (var e : root.getAsJsonArray("rulings")) {
            JsonObject o = e.getAsJsonObject();
            store.addRuling(o.get("id").getAsString(), projectId, o.get("derivedId").getAsString(),
                    o.get("ordinal").getAsInt(), o.get("decision").getAsString(),
                    strOrNull(o, "note"), strOrNull(o, "eventId"));
        }
    }

    private void insertEvent(String projectId, JsonObject o) {
        // Ordinal and id are preserved verbatim from the snapshot.
        String id = o.get("id").getAsString();
        int ordinal = o.get("ordinal").getAsInt();
        store.addEventWithId(id, projectId, ordinal, strOrNull(o, "revisionId"),
                o.get("type").getAsString(), o.get("payload").getAsString(),
                o.has("createdAt") && !o.get("createdAt").isJsonNull()
                        ? o.get("createdAt").getAsLong() : store.clock().nowMillis());
    }

    private static String strOrNull(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    public Path dbPath() {
        return dbPath;
    }

    @Override
    public void close() {
        store.close();
    }
}
