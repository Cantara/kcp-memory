package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.ToolUsage;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD operations for the tool_usages table.
 */
public class ToolUsageStore {

    private final Connection conn;

    public ToolUsageStore(MemoryDatabase db) {
        this.conn = db.getConnection();
    }

    /** Batch-insert tool usages for a session. */
    public void insertBatch(List<ToolUsage> usages) throws SQLException {
        if (usages == null || usages.isEmpty()) return;
        String sql = "INSERT INTO tool_usages (session_id, tool_name, tool_input, occurred_at) VALUES (?,?,?,?)";
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (ToolUsage u : usages) {
                ps.setString(1, u.getSessionId());
                ps.setString(2, u.getToolName());
                ps.setString(3, u.getToolInput());
                ps.setString(4, u.getOccurredAt());
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    /** Top N tools by usage count (across all sessions). */
    public List<ToolFrequency> topTools(int limit) throws SQLException {
        String sql = """
                SELECT tool_name, COUNT(*) AS cnt
                FROM tool_usages
                GROUP BY tool_name
                ORDER BY cnt DESC
                LIMIT ?
                """;
        List<ToolFrequency> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ToolFrequency(rs.getString("tool_name"), rs.getLong("cnt")));
                }
            }
        }
        return out;
    }

    /** Delete all tool usages for a session (used when re-indexing). */
    public void deleteForSession(String sessionId) throws SQLException {
        String sql = "DELETE FROM tool_usages WHERE session_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        }
    }

    public record ToolFrequency(String toolName, long count) {}
}
