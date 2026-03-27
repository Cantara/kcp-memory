package com.cantara.kcp.memory.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Logs kcp-memory MCP tool call events to ~/.kcp/usage.db (RFC-0017).
 * <p>
 * Events written:
 * <ul>
 *   <li>{@code search}   — written by kcp_memory_search and kcp_memory_events_search</li>
 *   <li>{@code get_unit} — written by kcp_memory_session_detail</li>
 * </ul>
 * Always non-blocking (virtual thread). Never throws. Never blocks the MCP response.
 */
public final class UsageLogger {

    static Path dbPath = Path.of(System.getProperty("user.home"), ".kcp", "usage.db");

    private static final ReentrantLock WRITE_LOCK  = new ReentrantLock();
    private static volatile boolean    initialized = false;

    private UsageLogger() {}

    /** Log a search event asynchronously — returns immediately. Use in long-lived processes. */
    public static void logSearch(String query, int resultCount) {
        Thread.ofVirtual().start(() -> {
            try {
                ensureSchema();
                String project = projectFromPwd();
                insert("search", project, query, null, resultCount, null, null, null);
            } catch (Exception e) {
                System.err.println("[kcp-memory UsageLogger] search failed: " + e);
            }
        });
    }

    /** Log a search event synchronously — blocks until written. Use in CLI (short-lived) processes. */
    public static void logSearchSync(String query, int resultCount) {
        try {
            ensureSchema();
            String project = projectFromPwd();
            insert("search", project, query, null, resultCount, null, null, null);
        } catch (Exception e) {
            System.err.println("[kcp-memory UsageLogger] search failed: " + e);
        }
    }

    /** Log a get_unit event asynchronously — returns immediately. */
    public static void logGet(String unitId) {
        Thread.ofVirtual().start(() -> {
            try {
                ensureSchema();
                String project = projectFromPwd();
                insert("get_unit", project, null, unitId, null, null, null, null);
            } catch (Exception e) {
                System.err.println("[kcp-memory UsageLogger] get failed: " + e);
            }
        });
    }

    private static String projectFromPwd() {
        String pwd = System.getenv("PWD");
        if (pwd == null || pwd.isBlank()) return "unknown";
        return Path.of(pwd).getFileName().toString();
    }

    private static void ensureSchema() throws Exception {
        if (initialized) return;
        WRITE_LOCK.lock();
        try {
            if (initialized) return;
            Files.createDirectories(dbPath.getParent());
            try (Connection conn = connect()) {
                conn.createStatement().executeUpdate("PRAGMA journal_mode=WAL");
                conn.createStatement().executeUpdate("""
                    CREATE TABLE IF NOT EXISTS usage_events (
                        id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp            TEXT    NOT NULL,
                        event_type           TEXT    NOT NULL,
                        project              TEXT,
                        query                TEXT,
                        unit_id              TEXT,
                        result_count         INTEGER,
                        token_estimate       INTEGER,
                        manifest_token_total INTEGER,
                        session_id           TEXT
                    )""");
                conn.createStatement().executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_usage_timestamp ON usage_events(timestamp)");
                conn.createStatement().executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_usage_type ON usage_events(event_type)");
                // Migration: add session_id if table predates RFC-0017
                try { conn.createStatement().executeUpdate(
                    "ALTER TABLE usage_events ADD COLUMN session_id TEXT"); }
                catch (Exception ignored) {}
            }
            initialized = true;
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static void insert(String eventType, String project, String query,
                                String unitId, Integer resultCount,
                                Integer tokenEstimate, Integer manifestTokenTotal,
                                String sessionId) throws Exception {
        WRITE_LOCK.lock();
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO usage_events
                    (timestamp, event_type, project, query, unit_id,
                     result_count, token_estimate, manifest_token_total, session_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, eventType);
            ps.setString(3, project);
            ps.setString(4, query);
            ps.setString(5, unitId);
            if (resultCount        != null) ps.setInt(6, resultCount);        else ps.setNull(6, java.sql.Types.INTEGER);
            if (tokenEstimate      != null) ps.setInt(7, tokenEstimate);      else ps.setNull(7, java.sql.Types.INTEGER);
            if (manifestTokenTotal != null) ps.setInt(8, manifestTokenTotal); else ps.setNull(8, java.sql.Types.INTEGER);
            ps.setString(9, sessionId);
            ps.executeUpdate();
        } finally {
            WRITE_LOCK.unlock();
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
    }
}
