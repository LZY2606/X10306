package smtroom.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class Db implements AutoCloseable {
    private final Connection conn;

    private Db(Connection conn) { this.conn = conn; }

    public static Db open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            c.createStatement().execute("PRAGMA foreign_keys=ON");
            Db db = new Db(c);
            db.migrate();
            return db;
        } catch (SQLException e) {
            throw new RuntimeException("open db: " + e.getMessage(), e);
        }
    }

    public Connection conn() { return conn; }

    private void migrate() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS inputs(
                  id TEXT PRIMARY KEY,
                  version INTEGER NOT NULL,
                  parent_id TEXT,
                  kind TEXT NOT NULL,
                  raw TEXT NOT NULL,
                  fingerprint TEXT NOT NULL,
                  submitted_at TEXT NOT NULL,
                  event_seq INTEGER NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS derived(
                  id TEXT PRIMARY KEY,
                  algorithm TEXT NOT NULL,
                  algo_version TEXT NOT NULL,
                  param_fingerprint TEXT NOT NULL,
                  source_ids TEXT NOT NULL,
                  payload TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  event_seq INTEGER NOT NULL,
                  stale INTEGER NOT NULL DEFAULT 0
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS events(
                  seq INTEGER PRIMARY KEY,
                  ts TEXT NOT NULL,
                  kind TEXT NOT NULL,
                  payload TEXT NOT NULL
                )""");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS decisions(
                  id TEXT PRIMARY KEY,
                  target_id TEXT NOT NULL,
                  verdict TEXT NOT NULL,
                  rationale TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  event_seq INTEGER NOT NULL,
                  superseded_by TEXT,
                  superseded_at_seq INTEGER
                )""");
        }
    }

    @Override public void close() {
        try { conn.close(); } catch (SQLException ignored) {}
    }
}
