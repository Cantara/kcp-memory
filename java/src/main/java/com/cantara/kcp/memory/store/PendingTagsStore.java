package com.cantara.kcp.memory.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Manages the pending_tags table — a staging area for tags written by the
 * UserPromptSubmit hook before the session is scanned into the sessions table.
 *
 * Flow:
 *   1. Hook fires at prompt submit → enqueue(sessionId, tags)
 *   2. Scanner runs → flush(sessionStore) applies pending tags to indexed sessions
 *      and drops rows older than 7 days (orphan cleanup)
 */
public class PendingTagsStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection conn;

    public PendingTagsStore(MemoryDatabase db) {
        this.conn = db.getConnection();
    }

    /**
     * Enqueue tags for a session. Idempotent: subsequent calls for the same
     * session_id merge tags (union, no duplicates) rather than replacing.
     */
    public void enqueue(String sessionId, List<String> tags) throws SQLException {
        List<String> existing = get(sessionId);
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(existing);
        merged.addAll(tags);
        String json = toJson(new java.util.ArrayList<>(merged));

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO pending_tags (session_id, tags) VALUES (?, ?)" +
                " ON CONFLICT(session_id) DO UPDATE SET tags = ?")) {
            ps.setString(1, sessionId);
            ps.setString(2, json);
            ps.setString(3, json);
            ps.executeUpdate();
        }
    }

    /**
     * Return the pending tags for a session_id, or empty list if none queued.
     */
    public List<String> get(String sessionId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tags FROM pending_tags WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? fromJson(rs.getString(1)) : List.of();
            }
        }
    }

    /**
     * Apply all pending tags to their sessions and clean up.
     * Called after each scan cycle so newly indexed sessions receive queued tags.
     *
     * Sessions that are not yet indexed are left in pending_tags.
     * Rows older than 7 days are dropped regardless (orphan cleanup).
     */
    public void flush(SessionStore sessionStore) throws SQLException {
        // Collect all pending rows
        List<Row> pending;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT session_id, tags FROM pending_tags")) {
            pending = new java.util.ArrayList<>();
            while (rs.next()) {
                pending.add(new Row(rs.getString(1), rs.getString(2)));
            }
        }

        List<String> applied = new java.util.ArrayList<>();
        for (Row row : pending) {
            List<String> tags = fromJson(row.tags());
            if (tags.isEmpty()) {
                applied.add(row.sessionId());
                continue;
            }
            // addTags returns false if session not yet indexed — leave it pending
            try {
                if (sessionStore.addTags(row.sessionId(), tags)) {
                    applied.add(row.sessionId());
                }
            } catch (SQLException ignored) {
                // ambiguous prefix won't happen for full UUIDs from hooks
            }
        }

        // Remove successfully applied rows
        if (!applied.isEmpty()) {
            for (String id : applied) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM pending_tags WHERE session_id = ?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
            }
        }

        // Orphan cleanup: drop rows older than 7 days
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM pending_tags WHERE created_at < ?")) {
            ps.setString(1, Instant.now().minus(7, ChronoUnit.DAYS).toString());
            ps.executeUpdate();
        }
    }

    private record Row(String sessionId, String tags) {}

    @SuppressWarnings("unchecked")
    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
