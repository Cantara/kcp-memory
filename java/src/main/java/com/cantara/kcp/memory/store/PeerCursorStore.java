package com.cantara.kcp.memory.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Tracks replication cursors per peer — how far we've synced sessions,
 * events, and agent sessions from each remote instance.
 *
 * <p>Uses the {@code peer_cursors} table (V6 migration).
 */
public class PeerCursorStore {

    private static final Logger LOG = Logger.getLogger(PeerCursorStore.class.getName());

    private final MemoryDatabase db;

    public PeerCursorStore(MemoryDatabase db) {
        this.db = db;
    }

    /** Get the last synced session timestamp for a peer, or null if never synced. */
    public String getLastSessionTs(String peerId) {
        return getCursorField(peerId, "last_session_ts");
    }

    /** Get the last synced event timestamp for a peer, or null if never synced. */
    public String getLastEventTs(String peerId) {
        return getCursorField(peerId, "last_event_ts");
    }

    /** Get the last synced agent session timestamp for a peer, or null if never synced. */
    public String getLastAgentTs(String peerId) {
        return getCursorField(peerId, "last_agent_ts");
    }

    /** Update the session sync cursor for a peer. */
    public void updateSessionCursor(String peerId, String timestamp) {
        upsertCursor(peerId, "last_session_ts", timestamp);
    }

    /** Update the event sync cursor for a peer. */
    public void updateEventCursor(String peerId, String timestamp) {
        upsertCursor(peerId, "last_event_ts", timestamp);
    }

    /** Update the agent session sync cursor for a peer. */
    public void updateAgentCursor(String peerId, String timestamp) {
        upsertCursor(peerId, "last_agent_ts", timestamp);
    }

    /** Get the last pushed session timestamp for a peer, or null if never pushed. */
    public String getLastPushSessionTs(String peerId) {
        return getCursorField(peerId, "last_push_session_ts");
    }

    /** Get the last pushed event timestamp for a peer, or null if never pushed. */
    public String getLastPushEventTs(String peerId) {
        return getCursorField(peerId, "last_push_event_ts");
    }

    /** Update the push session cursor for a peer. */
    public void updatePushSessionCursor(String peerId, String timestamp) {
        upsertCursor(peerId, "last_push_session_ts", timestamp);
    }

    /** Update the push event cursor for a peer. */
    public void updatePushEventCursor(String peerId, String timestamp) {
        upsertCursor(peerId, "last_push_event_ts", timestamp);
    }

    // --- internal ---

    private String getCursorField(String peerId, String field) {
        // field is always a compile-time constant from this class, safe for SQL
        String sql = "SELECT " + field + " FROM peer_cursors WHERE peer_id = ?";
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, peerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        } catch (SQLException e) {
            LOG.warning("Failed to read cursor for peer " + peerId + ": " + e.getMessage());
        }
        return null;
    }

    private void upsertCursor(String peerId, String field, String value) {
        // SQLite UPSERT: insert or update on conflict
        String sql = """
                INSERT INTO peer_cursors (peer_id, %s, updated_at)
                VALUES (?, ?, datetime('now'))
                ON CONFLICT(peer_id) DO UPDATE SET %s = excluded.%s, updated_at = datetime('now')
                """.formatted(field, field, field);
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            ps.setString(1, peerId);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warning("Failed to update cursor for peer " + peerId + ": " + e.getMessage());
        }
    }
}
