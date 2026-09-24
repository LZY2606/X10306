package smtw.engine;

import smtw.model.MeasureAnn;
import smtw.persist.ProjectRepository;
import smtw.sat.SearchParams;
import smtw.smt.SmtCommand;
import smtw.smt.TranscriptParser;
import smtw.verify.ResponseParser;
import smtw.verify.VerificationReport;
import smtw.verify.Verifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The review state machine.
 *
 * Raw inputs are immutable blobs (transcript / response / measures). Revisions create a new
 * blob with a parent link and invalidate ONLY derived snapshots that depend on them.
 * Derived values carry the algorithm version, parameter fingerprint and direct source event.
 * Decisions, conflicts, resolutions and undos are all written to the append-only event log.
 */
public final class ProjectService {

    public static final String KIND_TRANSCRIPT = "transcript.smt2";
    public static final String KIND_RESPONSE = "response.txt";
    public static final String KIND_MEASURES = "measures.json";
    public static final String MEASURES_ALGO = "measures/sidecar-v1";

    private final ProjectRepository repo;
    private final Clock clock;

    public ProjectService(ProjectRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public String createProject(String name, String casePackVersion) {
        long now = clock.nowMs();
        String id = "proj-" + StableId.shortHash(name + "|" + now);
        Project p = new Project(id, name, now, casePackVersion);
        ParamVersion pv0 = new ParamVersion("param-v1", 1, SearchParams.DEFAULT.atTime(now),
                null, "initial local-search parameters", now);
        Event e = new Event("", 0, now, Event.PROJECT_CREATED, null, null,
                pv0.id(), id, null,
                Json.io().toJson(Map.of("projectId", id, "name", name,
                        "params", pv0.params().fingerprint())),
                List.of(), "project created with default parameter set");
        repo.saveProject(p);
        repo.saveParam(pv0, id);
        repo.appendEvent(e);
        return id;
    }

    public Blob submitInput(String projectId, String kind, String filename, String rawText,
                            String parentBlobId, String reason) {
        String sha = StableId.sha256Hex(rawText);
        String id = "blob-" + kind.replace('.', '-') + "-" + StableId.shortHash(sha + "|" + parentBlobId);
        long now = clock.nowMs();
        Blob blob = new Blob(id, kind, filename, rawText, parentBlobId, now, sha);
        String type = KIND_RESPONSE.equals(kind) ? Event.INPUT_SUBMITTED
                : KIND_TRANSCRIPT.equals(kind) ? Event.INPUT_SUBMITTED : Event.INPUT_SUBMITTED;
        Event ev = new Event("", 0, now,
                type, id, parentBlobId, activeParam(projectId).id(),
                id, null,
                Json.io().toJson(Map.of("kind", kind, "filename", filename, "sha256", sha,
                        "parentBlobId", str(parentBlobId), "reason", str(reason))),
                List.of(), reason == null ? "raw input submitted (immutable)" : reason);
        repo.saveBlob(blob, projectId);
        repo.appendEvent(ev);
        replay(projectId, "inputs changed: " + kind);
        return blob;
    }

    public ParamVersion reviseParams(String projectId, SearchParams next, String reason) {
        ParamVersion cur = activeParam(projectId);
        int ver = cur.version() + 1;
        long now = clock.nowMs();
        String id = "param-v" + ver;
        ParamVersion pv = new ParamVersion(id, ver, next.atTime(now), cur.id(), reason, now);
        Event ev = new Event("", 0, now,
                Event.PARAM_REVISED, null, null, id, id, "ADJUST",
                Json.io().toJson(Map.of("fromFingerprint", cur.params().fingerprint(),
                        "toFingerprint", next.fingerprint())),
                List.of(), reason);
        repo.saveParam(pv, projectId);
        repo.appendEvent(ev);
        replay(projectId, "parameter revision -> all locally derived results re-materialised");
        return pv;
    }

    public void decide(String projectId, String subject, String candidateId,
                       String reviewer, String verdict, String note) {
        List<DecisionRecord> existing = repo.decisionsForSubject(projectId, subject);
        String id = "dec-" + StableId.shortHash(subject + "|" + verdict + "|" + reviewer + "|" + clock.nowMs());
        long now = clock.nowMs();
        String supersedes = existing.isEmpty() ? null : existing.get(existing.size() - 1).id();
        DecisionRecord d = new DecisionRecord(id, subject, candidateId, reviewer, verdict, note,
                eventId(projectId, repo.nextSeq(projectId)), now, supersedes);
        boolean conflict = existing.stream().anyMatch(x -> x.conflictsWith(d));
        String type = conflict ? Event.CONFLICT : Event.DECISION;
        Event ev = new Event("", 0, now,
                type, null, null, activeParam(projectId).id(), subject, verdict,
                Json.io().toJson(Map.of("decisionId", id, "candidateId", str(candidateId),
                        "reviewer", reviewer, "note", str(note), "conflict", conflict,
                        "supersedes", str(supersedes))),
                List.of(), note);
        repo.saveDecision(d, projectId);
        repo.appendEvent(ev);
    }

    public void resolveConflict(String projectId, String subject, String winningDecisionId, String note) {
        long now = clock.nowMs();
        Event ev = new Event("", 0, now,
                Event.RESOLVED, null, null, activeParam(projectId).id(), subject, "RESOLVE",
                Json.io().toJson(Map.of("winningDecisionId", winningDecisionId, "note", str(note))),
                List.of(), note == null ? "conflict resolved" : note);
        repo.appendEvent(ev);
    }

    public void undoLast(String projectId, String note) {
        List<Event> all = repo.events(projectId);
        Optional<Event> target = all.stream()
                .filter(e -> e.type().equals(Event.DECISION) || e.type().equals(Event.CONFLICT)
                        || e.type().equals(Event.RESOLVED))
                .reduce((a, b) -> b);
        long now = clock.nowMs();
        Event undo = new Event("", 0, now,
                Event.UNDO, null, null, activeParam(projectId).id(),
                target.map(Event::subject).orElse(projectId), null,
                Json.io().toJson(Map.of("undoneEventId", target.map(Event::id).orElse(""),
                        "note", str(note))),
                List.of(), note == null ? "undo recorded" : note);
        repo.appendEvent(undo);
    }

    /** Rebuild derived results from the newest blobs + active params; older snapshots stay readable. */
    public synchronized VerificationReport replay(String projectId, String reason) {
        Blob transcript = latestBlob(projectId, KIND_TRANSCRIPT);
        Blob response = latestBlob(projectId, KIND_RESPONSE);
        Blob measuresBlob = latestBlob(projectId, KIND_MEASURES);
        ParamVersion pv = activeParam(projectId);
        long now = clock.nowMs();
        if (transcript == null) {
            repo.appendEvent(new Event("", 0, now, Event.REPLAYED,
                    null, null, pv.id(), projectId, null,
                    Json.io().toJson(Map.of("state", "NO_TRANSCRIPT")), List.of(), reason));
            return null;
        }
        List<SmtCommand> commands = TranscriptParser.parse(transcript.rawText());
        List<ResponseParser.PairedResponse> responses = response == null
                ? List.of()
                : ResponseParser.parse(response.rawText(), commands);
        Map<String, MeasureAnn> measures = measuresBlob == null
                ? Map.of() : MeasuresJson.parse(measuresBlob.rawText());

        VerificationReport report = new Verifier(pv.params()).verify(transcript.id(),
                commands, responses, measures);

        Event replayEvent = repo.appendEvent(new Event("", 0, now, Event.REPLAYED,
                transcript.id(), response == null ? null : response.id(), pv.id(),
                projectId, null,
                Json.io().toJson(Map.of("checks", report.checks().size(),
                        "logicSupported", report.logicSupport().supported(),
                        "algoVersion", Verifier.ALGO_VERSION,
                        "paramFingerprint", pv.params().fingerprint())),
                List.of(), reason));

        materialise(projectId, report, transcript, response, measuresBlob, pv, replayEvent.id());
        return report;
    }

    private void materialise(String projectId, VerificationReport report,
                             Blob transcript, Blob response, Blob measuresBlob,
                             ParamVersion pv, String sourceEventId) {
        String modelBlobId = response == null ? null : response.id();
        List<DerivedSnapshot> fresh = new ArrayList<>();
        long now = clock.nowMs();

        // scope stack events
        report.scope().events().forEach(se -> {
            DerivedKey k = new DerivedKey(se.id(), transcript.id(), blobId(response),
                    null, pv.id(), Verifier.ALGO_VERSION);
            fresh.add(snapshot(k, "SCOPE", Json.io().toJson(se), sourceEventId, now));
        });
        report.checks().forEach(cv -> {
            DerivedKey k = new DerivedKey(cv.id(), transcript.id(), blobId(response),
                    modelBlobId, pv.id(), Verifier.ALGO_VERSION);
            fresh.add(snapshot(k, "CHECK", Json.io().toJson(cv), sourceEventId, now));
            cv.modelTrees().forEach(tree -> {
                DerivedKey tk = new DerivedKey(tree.id(), transcript.id(), blobId(response),
                        modelBlobId, pv.id(), Verifier.ALGO_VERSION);
                fresh.add(snapshot(tk, "MODEL_TREE", Json.io().toJson(tree), sourceEventId, now));
            });
            if (cv.coreVerification() != null) {
                DerivedKey ck = new DerivedKey(cv.coreVerification().id(), transcript.id(),
                        blobId(response), modelBlobId, pv.id(), smtw.core.CoreVerifier.ALGO_VERSION);
                fresh.add(snapshot(ck, "CORE", Json.io().toJson(cv.coreVerification()),
                        sourceEventId, now));
            }
        });

        List<DerivedSnapshot> prior = repo.snapshots(projectId);
        Map<String, DerivedSnapshot> priorById = new LinkedHashMap<>();
        prior.forEach(s -> priorById.put(s.id(), s));
        List<String> invalidated = new ArrayList<>();
        for (DerivedSnapshot s : fresh) priorById.put(s.id(), s);
        // A prior snapshot is stale when its subject now has a newer snapshot from different
        // blob/param versions.
        Map<String, List<DerivedSnapshot>> bySubject = new LinkedHashMap<>();
        priorById.values().forEach(s -> bySubject
                .computeIfAbsent(s.key().subject(), x -> new ArrayList<>()).add(s));
        for (List<DerivedSnapshot> list : bySubject.values()) {
            list.sort(Comparator.comparing(s -> s.producedAtMs()));
            for (int i = 0; i < list.size(); i++) {
                DerivedSnapshot s = list.get(i);
                boolean current = i == list.size() - 1
                        && s.key().transcriptBlobId().equals(transcript.id())
                        && eq(s.key().responseBlobId(), blobId(response))
                        && s.key().paramVersionId().equals(pv.id());
                DerivedSnapshot marked = current ? s : new DerivedSnapshot(s.id(), s.key(), s.kind(),
                        s.payloadJson(), s.directSourceEventId(), s.producedAtMs(), false,
                        s.staleReason() == null ? "superseded by newer inputs or parameters" : s.staleReason());
                if (!current && s.current()) invalidated.add(s.key().subject());
                repo.upsertSnapshot(marked, projectId);
            }
        }
        repo.appendEvent(new Event("", 0, clock.nowMs(), Event.DERIVED,
                transcript.id(), blobId(response), pv.id(), projectId, null,
                Json.io().toJson(Map.of("snapshots", fresh.size(), "invalidated", invalidated)),
                invalidated, "derived results materialised; stale results retained read-only"));
    }

    private DerivedSnapshot snapshot(DerivedKey k, String kind, String json, String sourceEventId, long now) {
        return new DerivedSnapshot(k.stableId(), k, kind, json, sourceEventId, now, true, null);
    }

    public ProjectView view(String projectId) {
        Project p = repo.project(projectId);
        List<Blob> blobs = repo.blobs(projectId);
        List<ParamVersion> params = repo.params(projectId);
        ParamVersion active = params.stream().max(Comparator.comparingInt(ParamVersion::version)).orElseThrow();
        List<Event> events = repo.events(projectId);
        List<DerivedSnapshot> snaps = repo.snapshots(projectId);
        List<DecisionRecord> decisions = repo.allDecisions(projectId);
        VerificationReport report = reportFromCurrentSnapshots(projectId, snaps, active);
        return new ProjectView(p, blobs, params, active, events, snaps, decisions, report);
    }

    /** Reconstruct a view AS OF an event: later derived snapshots and decisions are hidden. */
    public ProjectView viewAsOf(String projectId, String eventId) {
        ProjectView base = view(projectId);
        long cutoff = base.events().stream().filter(e -> e.id().equals(eventId))
                .mapToLong(Event::seq).findFirst().orElse(Long.MAX_VALUE);
        long cutoffMs = base.events().stream().filter(e -> e.seq() <= cutoff)
                .mapToLong(Event::atMs).max().orElse(0L);
        List<Event> events = base.events().stream().filter(e -> e.seq() <= cutoff).toList();
        java.util.Set<String> visibleEvents = new java.util.HashSet<>();
        events.forEach(e -> visibleEvents.add(e.id()));
        List<DerivedSnapshot> snaps = base.snapshots().stream()
                .filter(s -> visibleEvents.contains(s.directSourceEventId()))
                .toList();
        List<DecisionRecord> decisions = base.decisions().stream()
                .filter(d -> visibleEvents.contains(d.eventId())).toList();
        ParamVersion pv = base.paramVersions().stream()
                .filter(p -> p.atMs() <= cutoffMs)
                .max(Comparator.comparingInt(ParamVersion::version))
                .orElse(base.paramVersions().get(0));
        return new ProjectView(base.project(), base.blobs(), base.paramVersions(),
                pv, events, snaps, decisions, base.currentReport());
    }

    private VerificationReport reportFromCurrentSnapshots(String projectId, List<DerivedSnapshot> snaps,
                                                         ParamVersion active) {
        Blob transcript = latestBlob(projectId, KIND_TRANSCRIPT);
        if (transcript == null) return null;
        List<SmtCommand> commands = TranscriptParser.parse(transcript.rawText());
        Blob response = latestBlob(projectId, KIND_RESPONSE);
        List<ResponseParser.PairedResponse> responses = response == null
                ? List.of() : ResponseParser.parse(response.rawText(), commands);
        Map<String, MeasureAnn> m = new LinkedHashMap<>();
        Blob mb = latestBlob(projectId, KIND_MEASURES);
        if (mb != null) m.putAll(MeasuresJson.parse(mb.rawText()));
        return new Verifier(active.params()).verify(transcript.id(), commands, responses, m);
    }

    private Blob latestBlob(String projectId, String kind) {
        return repo.blobs(projectId).stream()
                .filter(b -> b.kind().equals(kind))
                .max(Comparator.comparingLong(Blob::submittedAtMs))
                .orElse(null);
    }

    private ParamVersion activeParam(String projectId) {
        return repo.params(projectId).stream()
                .max(Comparator.comparingInt(ParamVersion::version)).orElseThrow();
    }

    private static String blobId(Blob b) { return b == null ? null : b.id(); }
    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }
    private static String str(String s) { return s == null ? "" : s; }
}
