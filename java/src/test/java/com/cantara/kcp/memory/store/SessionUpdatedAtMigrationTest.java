package com.cantara.kcp.memory.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * V13 adds sessions.updated_at, the change marker peer sync pages through, and backfills it
 * from scanned_at. scanned_at holds two shapes in real databases: the ISO form the scanner
 * writes (with a variable number of fractional digits) and SQLite's datetime('now') form that
 * peer inserts write. Both must come out in the one canonical form.
 */
class SessionUpdatedAtMigrationTest {

    @TempDir Path tmp;

    private static String text(MemoryDatabase db, String sql) throws Exception {
        try (Statement st = db.getConnection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void backfillsUpdatedAtFromScannedAtInBothShapes() throws Exception {
        Path file = tmp.resolve("memory.db");
        MemoryDatabase db = new MemoryDatabase(file);
        for (String[] row : new String[][]{
                {"iso-nanos", "2026-11-23T07:21:57.123456789Z"},
                {"iso-whole", "2026-11-23T07:21:57Z"},
                {"sqlite-now", "2026-11-23 07:21:57"}}) {
            try (var ps = db.getConnection().prepareStatement(
                    "INSERT INTO sessions (session_id, project_dir, started_at, scanned_at) VALUES (?, '/x', '2026-11-01T00:00:00Z', ?)")) {
                ps.setString(1, row[0]);
                ps.setString(2, row[1]);
                ps.executeUpdate();
            }
        }
        // Make this a database from before V13: no column, migration not recorded.
        try (Statement st = db.getConnection().createStatement()) {
            st.execute("ALTER TABLE sessions DROP COLUMN updated_at");
            st.execute("DELETE FROM schema_migrations WHERE version = 'V13__session_updated_at'");
        }
        db.close();

        MemoryDatabase upgraded = new MemoryDatabase(file);
        try {
            assertEquals("2026-11-23T07:21:57.123Z", text(upgraded, "SELECT updated_at FROM sessions WHERE session_id = 'iso-nanos'"));
            assertEquals("2026-11-23T07:21:57.000Z", text(upgraded, "SELECT updated_at FROM sessions WHERE session_id = 'iso-whole'"));
            assertEquals("2026-11-23T07:21:57.000Z", text(upgraded, "SELECT updated_at FROM sessions WHERE session_id = 'sqlite-now'"));
        } finally {
            upgraded.close();
        }
    }
}
