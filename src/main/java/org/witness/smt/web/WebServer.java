package org.witness.smt.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.witness.smt.replay.Replayer;
import org.witness.smt.replay.ReplayState;
import org.witness.smt.replay.Transcript;
import org.witness.smt.service.RevisionAnalysis;
import org.witness.smt.service.RevisionAnalysis.Analysis;
import org.witness.smt.service.WitnessService;
import org.witness.smt.semantics.BoundedSolver;
import org.witness.smt.store.Clock;

/** HTTP layer for the witness room review UI. */
public final class WebServer {

    private final WitnessService service;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private Javalin app;

    public WebServer(WitnessService service) {
        this.service = service;
    }

    public Javalin start(String host, int port) {
        app = Javalin.create(cfg -> cfg.staticFiles.add("/web"));
        routes(app);
        app.start(host, port);
        return app;
    }

    private void routes(Javalin app) {
        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

        app.get("/api/projects", ctx -> {
            List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (String[] p : service.store().listProjects()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p[0]);
                m.put("name", p[1]);
                out.add(m);
            }
            ctx.json(out);
        });

        app.post("/api/projects/{id}/imports", ctx -> {
            String projectId = ctx.pathParam("id");
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String name = body.get("name").getAsString();
            String raw = body.get("rawText").getAsString();
            String kind = body.has("kind") ? body.get("kind").getAsString() : "initial";
            String label = body.has("label") ? body.get("label").getAsString() : "initial import";
            String parent = body.has("parentId") && !body.get("parentId").isJsonNull()
                    ? body.get("parentId").getAsString() : null;
            try {
                var ref = service.importTranscript(projectId, name, raw, kind, label, parent);
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("importId", ref.importId());
                resp.put("version", ref.version());
                resp.put("parentId", ref.parentId());
                ctx.status(HttpStatus.CREATED).json(resp);
            } catch (Transcript.ParseException e) {
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of(
                        "error", "format-error", "message", e.getMessage(), "line", e.line));
            }
        });

        app.post("/api/projects/{id}/params", ctx -> {
            String projectId = ctx.pathParam("id");
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String revisionId = body.get("revisionId").getAsString();
            int ib = body.get("intBound").getAsInt();
            int rg = body.get("realGrid").getAsInt();
            int mc = body.get("maxCandidates").getAsInt();
            String eventId = service.reviseParameters(projectId, revisionId,
                    new BoundedSolver.Params(ib, rg, mc));
            ctx.status(HttpStatus.CREATED).json(Map.of("eventId", eventId));
        });

        app.post("/api/projects/{id}/rulings", ctx -> {
            String projectId = ctx.pathParam("id");
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            String rulingId = service.rule(projectId, body.get("revisionId").getAsString(),
                    body.get("decision").getAsString(),
                    body.has("note") && !body.get("note").isJsonNull() ? body.get("note").getAsString() : "",
                    body.get("derivedId").getAsString());
            ctx.status(HttpStatus.CREATED).json(Map.of("rulingId", rulingId));
        });

        app.get("/api/projects/{id}/snapshot", ctx -> {
            String projectId = ctx.pathParam("id");
            String revisionId = ctx.queryParam("revisionId");
            int ib = intParam(ctx, "intBound", 3);
            int rg = intParam(ctx, "realGrid", 2);
            int mc = intParam(ctx, "maxCandidates", 5);
            if (revisionId == null) {
                revisionId = service.store().headRevisionId(projectId);
            }
            if (revisionId == null) {
                ctx.status(HttpStatus.NOT_FOUND).json(Map.of("error", "no revisions"));
                return;
            }
            Analysis analysis = service.analysisFor(projectId, revisionId,
                    new BoundedSolver.Params(ib, rg, mc));
            ctx.json(buildSnapshot(projectId, revisionId, analysis, ib, rg, mc));
        });

        app.get("/api/projects/{id}/export", ctx -> {
            ctx.contentType("application/json").result(service.exportProject(ctx.pathParam("id")));
        });

        app.post("/api/import-bundle", ctx -> {
            service.importProject(ctx.body());
            ctx.status(HttpStatus.CREATED).json(Map.of("status", "imported"));
        });
    }

    private static int intParam(io.javalin.http.Context ctx, String name, int dflt) {
        String v = ctx.queryParam(name);
        return v == null ? dflt : Integer.parseInt(v);
    }

    Map<String, Object> buildSnapshot(String projectId, String revisionId, Analysis a,
                                      int intBound, int realGrid, int maxCandidates) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("projectId", projectId);
        snap.put("revisionId", revisionId);
        snap.put("logic", a.state().logic());
        snap.put("depth", a.state().depth());
        snap.put("params", Map.of("intBound", intBound, "realGrid", realGrid, "maxCandidates", maxCandidates));
        snap.put("paramFingerprint", new BoundedSolver.Params(intBound, realGrid, maxCandidates).fingerprint());

        snap.put("entries", entryViews(a));
        snap.put("frames", frameViews(a));
        snap.put("assertions", a.assertions());
        snap.put("checks", a.checks());
        snap.put("models", a.models());
        snap.put("cores", a.cores());
        snap.put("unsupportedTheories", a.state().unsupportedTheories());
        snap.put("replayProblems", a.replayer().problems);
        snap.put("candidates", a.candidates());

        Map<String, Object> units = new LinkedHashMap<>();
        Map<String, Object> basis = new LinkedHashMap<>();
        a.replayer().units.symbols().forEach(sym -> {
            var b = a.replayer().units.basisOf(sym);
            basis.put(sym, Map.of("symbol", b.symbol(), "rawUnit", b.rawUnit(), "rawText", b.rawText()));
        });
        units.put("basis", basis);
        snap.put("units", units);

        snap.put("imports", service.store().imports(projectId));
        snap.put("events", service.store().events(projectId));
        snap.put("derived", service.store().derived(projectId));
        snap.put("rulings", service.store().rulings(projectId));
        return snap;
    }

    private List<Map<String, Object>> entryViews(Analysis a) {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        Transcript t = a.transcript();
        Replayer r = a.replayer();
        for (Transcript.Entry e : t.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", Replayer.idOf(e.index));
            m.put("type", e.type.name());
            m.put("raw", e.raw);
            m.put("sourceLine", e.sourceLine);
            if (e.commandName != null) {
                m.put("command", e.commandName);
            }
            if (e.resultKind != null) {
                m.put("result", e.resultKind.name());
            }
            if (e.annotKey != null) {
                m.put("annotation", Map.of("key", e.annotKey, "symbol", e.annotSymbol, "value", e.annotValue));
            }
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> frameViews(Analysis a) {
        // Rebuild frames with their assertion ids from the replayed state.
        List<ReplayState.Assertion> all = a.state().activeAssertions();
        Map<Integer, List<String>> byDepth = new LinkedHashMap<>();
        for (ReplayState.Assertion asrt : all) {
            byDepth.computeIfAbsent(asrt.frameDepth, k -> new java.util.ArrayList<>()).add(asrt.entryId);
        }
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (int d = 0; d <= a.state().depth(); d++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("depth", d);
            m.put("assertionIds", byDepth.getOrDefault(d, List.of()));
            out.add(m);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        String host = "127.0.0.1";
        int port = 5392;
        Path db = Path.of("witness-room.db");
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--host" -> host = args[i + 1];
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--db" -> db = Path.of(args[i + 1]);
                default -> { }
            }
        }
        WitnessService svc = new WitnessService(db, Clock.system());
        new WebServer(svc).start(host, port);
    }
}
