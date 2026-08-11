package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.CommandPattern;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Mines tool_events for recurring command patterns — the same command appearing
 * identically across multiple distinct sessions — as candidates for
 * {@code kcp-memory suggest-skill}. Deliberately narrow (exact command match, not
 * LLM summarization): the goal is generate-then-human-review, not auto-authored skills.
 */
public class PatternStore {

    private final MemoryDatabase db;

    public PatternStore(MemoryDatabase db) {
        this.db = db;
    }

    /**
     * Find commands that recur identically across at least {@code minSessions} distinct
     * sessions within the last {@code sinceDays} days.
     *
     * @param sinceDays   only consider events from the last N days
     * @param minSessions exclude commands seen in fewer than this many distinct sessions
     * @param limit       return at most this many patterns
     * @return patterns ranked by session count (most-recurring first), then occurrence count
     */
    public List<CommandPattern> findRecurringCommands(int sinceDays, int minSessions, int limit) throws SQLException {
        String cutoff = Instant.now().minus(sinceDays, ChronoUnit.DAYS).toString();
        Connection conn = db.getConnection();

        String sql = """
                SELECT command,
                       COUNT(*)                  AS occurrence_count,
                       COUNT(DISTINCT session_id) AS session_count,
                       MIN(event_ts)              AS first_seen,
                       MAX(event_ts)              AS last_seen
                FROM   tool_events
                WHERE  event_ts >= ?
                  AND  command IS NOT NULL
                  AND  trim(command) != ''
                GROUP  BY command
                HAVING COUNT(DISTINCT session_id) >= ?
                ORDER  BY session_count DESC, occurrence_count DESC, last_seen DESC
                LIMIT  ?
                """;

        List<CommandPattern> patterns = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoff);
            ps.setInt(2, minSessions);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String command = rs.getString("command");
                    patterns.add(new CommandPattern(
                            command,
                            rs.getInt("occurrence_count"),
                            rs.getInt("session_count"),
                            sessionIdsFor(conn, command, cutoff),
                            rs.getString("first_seen"),
                            rs.getString("last_seen")
                    ));
                }
            }
        }
        return patterns;
    }

    private List<String> sessionIdsFor(Connection conn, String command, String cutoff) throws SQLException {
        String sql = """
                SELECT DISTINCT session_id
                FROM   tool_events
                WHERE  command = ? AND event_ts >= ?
                ORDER  BY session_id
                """;
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, command);
            ps.setString(2, cutoff);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
        }
        return ids;
    }
}
