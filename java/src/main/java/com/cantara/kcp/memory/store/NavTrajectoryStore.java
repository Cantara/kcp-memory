package com.cantara.kcp.memory.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD operations for the nav_trajectory table (V10).
 * <p>
 * Backs kcp_memory_ls / kcp_memory_tree / kcp_memory_read (writes, via
 * {@link #record}) and kcp_memory_nav_history (reads, via {@link #recent}).
 * See {@code com.cantara.kcp.memory.mcp.NavigationTools} for the virtual
 * path scheme and the fail-safe wrapper around {@link #record}.
 */
public class NavTrajectoryStore {

    private final Connection conn;

    public NavTrajectoryStore(MemoryDatabase db) {
        this.conn = db.getConnection();
    }

    /** Append one navigation call to the trajectory log. */
    public void record(String tool, String path, String sessionId, String summary) throws SQLException {
        String sql = """
                INSERT INTO nav_trajectory (ts, tool, path, session_id, summary)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, tool);
            ps.setString(3, path);
            ps.setString(4, sessionId);
            ps.setString(5, summary);
            ps.executeUpdate();
        }
    }

    /**
     * Most recent trajectory entries, newest first, optionally filtered by
     * caller-supplied session id and/or a minimum ISO-8601 UTC timestamp.
     */
    public List<Entry> recent(int limit, String sessionId, String sinceIso) throws SQLException {
        boolean bySession = sessionId != null && !sessionId.isBlank();
        boolean bySince   = sinceIso  != null && !sinceIso.isBlank();

        StringBuilder sql = new StringBuilder(
                "SELECT id, ts, tool, path, session_id, summary FROM nav_trajectory WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (bySession) {
            sql.append(" AND session_id = ?");
            params.add(sessionId);
        }
        if (bySince) {
            sql.append(" AND ts >= ?");
            params.add(sinceIso);
        }
        sql.append(" ORDER BY ts DESC LIMIT ?");
        params.add(limit);

        List<Entry> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Entry(
                            rs.getLong("id"),
                            rs.getString("ts"),
                            rs.getString("tool"),
                            rs.getString("path"),
                            rs.getString("session_id"),
                            rs.getString("summary")));
                }
            }
        }
        return out;
    }

    /** One recorded navigation call. */
    public record Entry(
            long id,
            String ts,
            String tool,
            String path,
            String sessionId,
            String summary
    ) {}
}
