package org.witness.smt.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed event store. Everything the UI shows is reproducible from the
 * ordered event log: immutable imports, derived results (algorithm version +
 * parameter fingerprint + direct sources), rulings and exports.
 */
public final class ProjectStore implements AutoCloseable {

    private final Connection conn;
    private final Clock clock;
    public final String algorithmVersion = "local-subset-1.0.0";

    public ProjectStore(Path dbPath, Clock clock) {
        try {
            Files.createDirectories(dbPath.toAbsolutePath().getParent());
            this.conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            this.conn.createStatement().execute("PRAGMA foreign_keys = ON");
        } catch (Exception e) {
            throw new RuntimeException("cannot open database " + dbPath, e);
        }
        this.clock = clock;
        migrate();
    }

    public Clock clock() {
        return clock;
    }

    public Connection connection() {
        return conn;
    }

    private void migrate() {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS projects (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS imports (
                        id TEXT PRIMARY KEY,
                        project_id TEXT NOT NULL REFERENCES projects(id),
                        version INTEGER NOT NULL,
                        parent_id TEXT,
                        kind TEXT NOT NULL,
                        label TEXT NOT NULL,
                        raw_text TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        UNIQUE(project_id, version)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS events (
                        id TEXT PRIMARY KEY,
                        project_id TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        revision_id TEXT,
                        type TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        UNIQUE(project_id, ordinal)
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS derived (
                        id TEXT PRIMARY KEY,
                        project_id TEXT NOT NULL,
                        revision_id TEXT NOT NULL,
                        check_seq INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        status TEXT NOT NULL,
                        algorithm_version TEXT NOT NULL,
                        param_fingerprint TEXT NOT NULL,
                        sources TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS rulings (
                        id TEXT PRIMARY KEY,
                        project_id TEXT NOT NULL,
                        derived_id TEXT NOT NULL,
                        ordinal INTEGER NOT NULL,
                        decision TEXT NOT NULL,
                        note TEXT,
                        event_id TEXT,
                        created_at INTEGER NOT NULL,
                        UNIQUE(project_id, ordinal)
                    )""");
        } catch (SQLException e) {
            throw new RuntimeException("migration failed", e);
        }
    }

    public void createProject(String id, String name) {
        execute("INSERT INTO projects(id, name, created_at) VALUES(?,?,?)", id, name, clock.nowMillis());
    }

    public boolean projectExists(String id) {
        return queryOne("SELECT id FROM projects WHERE id = ?", id) != null;
    }

    public int nextImportVersion(String projectId) {
        Integer max = queryInt("SELECT COALESCE(MAX(version), 0) FROM imports WHERE project_id = ?", projectId);
        return (max == null ? 0 : max) + 1;
    }

    public void addImport(String id, String projectId, int version, String parentId,
                          String kind, String label, String rawText) {
        execute("INSERT INTO imports(id, project_id, version, parent_id, kind, label, raw_text, created_at) "
                + "VALUES(?,?,?,?,?,?,?,?)", id, projectId, version, parentId, kind, label, rawText,
                clock.nowMillis());
    }

    public int nextEventOrdinal(String projectId) {
        Integer max = queryInt("SELECT COALESCE(MAX(ordinal), -1) FROM events WHERE project_id = ?", projectId);
        return (max == null ? -1 : max) + 1;
    }

    public String addEvent(String projectId, String revisionId, String type, String payloadJson) {
        int ordinal = nextEventOrdinal(projectId);
        String id = Ids.eventId(projectId, ordinal);
        execute("INSERT INTO events(id, project_id, ordinal, revision_id, type, payload, created_at) "
                + "VALUES(?,?,?,?,?,?,?)", id, projectId, ordinal, revisionId, type, payloadJson,
                clock.nowMillis());
        return id;
    }

    public void addDerived(String id, String projectId, String revisionId, int checkSeq, String kind,
                           String status, String paramFingerprint, String sources, String payloadJson) {
        execute("INSERT INTO derived(id, project_id, revision_id, check_seq, kind, status, algorithm_version, "
                + "param_fingerprint, sources, payload, created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id, projectId, revisionId, checkSeq, kind, status, algorithmVersion,
                paramFingerprint, sources, payloadJson, clock.nowMillis());
    }

    public void addEventWithId(String id, String projectId, int ordinal, String revisionId,
                               String type, String payloadJson, long createdAt) {
        execute("INSERT INTO events(id, project_id, ordinal, revision_id, type, payload, created_at) "
                + "VALUES(?,?,?,?,?,?,?)", id, projectId, ordinal, revisionId, type, payloadJson, createdAt);
    }

    public int nextRulingOrdinal(String projectId) {
        Integer max = queryInt("SELECT COALESCE(MAX(ordinal), -1) FROM rulings WHERE project_id = ?", projectId);
        return (max == null ? -1 : max) + 1;
    }

    public void addRuling(String id, String projectId, String derivedId, int ordinal,
                          String decision, String note, String eventId) {
        execute("INSERT INTO rulings(id, project_id, derived_id, ordinal, decision, note, event_id, created_at) "
                + "VALUES(?,?,?,?,?,?,?,?)", id, projectId, derivedId, ordinal, decision, note, eventId,
                clock.nowMillis());
    }

    public List<String[]> listProjects() {
        List<String[]> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM projects ORDER BY created_at, id")) {
            while (rs.next()) {
                out.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public List<Object[]> imports(String projectId) {
        return queryRows("SELECT id, version, parent_id, kind, label, raw_text FROM imports "
                        + "WHERE project_id = ? ORDER BY version", projectId);
    }

    public List<Object[]> events(String projectId) {
        return queryRows("SELECT id, ordinal, revision_id, type, payload, created_at FROM events "
                + "WHERE project_id = ? ORDER BY ordinal", projectId);
    }

    public List<Object[]> derived(String projectId) {
        return queryRows("SELECT id, revision_id, check_seq, kind, status, algorithm_version, "
                + "param_fingerprint, sources, payload FROM derived WHERE project_id = ? ORDER BY check_seq, kind",
                projectId);
    }

    public List<Object[]> rulings(String projectId) {
        return queryRows("SELECT id, derived_id, ordinal, decision, note, event_id FROM rulings "
                + "WHERE project_id = ? ORDER BY ordinal", projectId);
    }

    public String headRevisionId(String projectId) {
        return queryString("SELECT id FROM imports WHERE project_id = ? ORDER BY version DESC LIMIT 1", projectId);
    }

    private List<Object[]> queryRows(String sql, String arg) {
        List<Object[]> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, arg);
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Object[] row = new Object[n];
                    for (int i = 0; i < n; i++) {
                        row[i] = rs.getObject(i + 1);
                    }
                    out.add(row);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    private void execute(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String queryString(String sql, String arg) {
        Object o = queryOne(sql, arg);
        return o == null ? null : o.toString();
    }

    private Integer queryInt(String sql, Object... args) {
        Object o = queryOne(sql, args);
        return o == null ? null : ((Number) o).intValue();
    }

    private Object queryOne(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    @Override
    public void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
