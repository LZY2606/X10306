package witnessroom.store;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public final class Db {
    public static Connection open(String path) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + path);
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA foreign_keys=ON");
            }
            migrate(c);
            return c;
        } catch (Exception e) {
            throw new RuntimeException("cannot open database " + path, e);
        }
    }

    public static void migrate(Connection c) {
        String[] ddl = {
            "CREATE TABLE IF NOT EXISTS projects(" +
                "id TEXT PRIMARY KEY, name TEXT NOT NULL, created_seq INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS counters(" +
                "project_id TEXT NOT NULL, kind TEXT NOT NULL, next INTEGER NOT NULL," +
                "PRIMARY KEY(project_id, kind))",
            "CREATE TABLE IF NOT EXISTS inputs(" +
                "id TEXT PRIMARY KEY, project_id TEXT NOT NULL, parent_id TEXT," +
                "kind TEXT NOT NULL, label TEXT NOT NULL, content TEXT NOT NULL," +
                "content_hash TEXT NOT NULL, seq INTEGER NOT NULL, actor TEXT NOT NULL," +
                "created_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS results(" +
                "id TEXT PRIMARY KEY, project_id TEXT NOT NULL, kind TEXT NOT NULL," +
                "algorithm TEXT NOT NULL, algorithm_version TEXT NOT NULL," +
                "param_fingerprint TEXT NOT NULL, sources TEXT NOT NULL," +
                "value_json TEXT NOT NULL, seq INTEGER NOT NULL, stale_since INTEGER)",
            "CREATE TABLE IF NOT EXISTS decisions(" +
                "id TEXT PRIMARY KEY, project_id TEXT NOT NULL, target_id TEXT NOT NULL," +
                "verdict TEXT NOT NULL, rationale TEXT NOT NULL, actor TEXT NOT NULL," +
                "seq INTEGER NOT NULL, superseded_seq INTEGER)",
            "CREATE TABLE IF NOT EXISTS events(" +
                "seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL," +
                "project_id TEXT NOT NULL, type TEXT NOT NULL, payload TEXT NOT NULL," +
                "actor TEXT NOT NULL, logical_time TEXT NOT NULL)"
        };
        try (Statement s = c.createStatement()) {
            for (String d : ddl) s.execute(d);
        } catch (Exception e) {
            throw new RuntimeException("migration failed", e);
        }
    }
}
