package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.ToolEvent;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD for the tool_events table and its FTS5 index.
 * Also manages the event_log_cursor (byte offset into ~/.kcp/events.jsonl).
 */
public class EventStore {

    private final MemoryDatabase db;

    public EventStore(MemoryDatabase db) {
        this.db = db;
    }

    /** Insert an event. Silently ignores duplicates (ON CONFLICT IGNORE). */
    public void insert(ToolEvent e) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO tool_events
                    (event_ts, session_id, project_dir, tool, command, manifest_key, manifest_version, ingested_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, e.eventTs());
            ps.setString(2, e.sessionId());
            ps.setString(3, e.projectDir());
            ps.setString(4, e.tool());
            ps.setString(5, e.command());
            ps.setString(6, e.manifestKey());
            ps.setString(7, e.manifestVersion());
            ps.setString(8, Instant.now().toString());
            ps.executeUpdate();
        }
    }

    /** Full-text search over command + project_dir. Returns newest-first. */
    public List<ToolEvent> search(String query, int limit) throws SQLException {
        String sql = """
                SELECT te.*
                FROM   tool_events_fts f
                JOIN   tool_events te ON te.id = f.rowid
                WHERE  tool_events_fts MATCH ?
                ORDER  BY te.event_ts DESC
                LIMIT  ?
                """;
        List<ToolEvent> results = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        }
        return results;
    }

    /** List recent events, optionally filtered by project. */
    public List<ToolEvent> list(String projectDir, int limit) throws SQLException {
        String sql = projectDir != null
                ? "SELECT * FROM tool_events WHERE project_dir = ? ORDER BY event_ts DESC LIMIT ?"
                : "SELECT * FROM tool_events ORDER BY event_ts DESC LIMIT ?";
        List<ToolEvent> results = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            if (projectDir != null) {
                ps.setString(1, projectDir);
                ps.setInt(2, limit);
            } else {
                ps.setInt(1, limit);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) results.add(mapRow(rs));
            }
        }
        return results;
    }

    /** Total number of indexed events. */
    public long count() throws SQLException {
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tool_events")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Current byte offset into ~/.kcp/events.jsonl. */
    public long getByteOffset() throws SQLException {
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT byte_offset FROM event_log_cursor WHERE id=1")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    /** Persist updated byte offset after a scan. */
    public void setByteOffset(long offset) throws SQLException {
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "UPDATE event_log_cursor SET byte_offset=? WHERE id=1")) {
            ps.setLong(1, offset);
            ps.executeUpdate();
        }
    }

    /**
     * Set the output_preview for the most recent matching event (by session + command)
     * that doesn't already have a preview. Used by EventLogScanner to process
     * {@code type:"output"} lines written by the PostToolUse hook.
     */
    public void updateOutputPreview(String sessionId, String command, String preview) throws SQLException {
        String sql = """
                UPDATE tool_events SET output_preview = ?
                WHERE id = (
                    SELECT id FROM tool_events
                    WHERE session_id = ? AND command = ? AND output_preview IS NULL
                    ORDER BY event_ts DESC LIMIT 1
                )
                """;
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, preview);
            ps.setString(2, sessionId);
            ps.setString(3, command);
            ps.executeUpdate();
        }
    }

    private ToolEvent mapRow(ResultSet rs) throws SQLException {
        return new ToolEvent(
                rs.getLong("id"),
                rs.getString("event_ts"),
                rs.getString("session_id"),
                rs.getString("project_dir"),
                rs.getString("tool"),
                rs.getString("command"),
                rs.getString("manifest_key"),
                rs.getString("output_preview"),
                rs.getString("manifest_version"),
                rs.getString("ingested_at")
        );
    }
}
