package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.AgentSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD operations for the agent_sessions table.
 */
public class AgentSessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection conn;

    public AgentSessionStore(MemoryDatabase db) {
        this.conn = db.getConnection();
    }

    /** Insert or update an agent session (upsert by agent_id). */
    public void upsert(AgentSession a) throws SQLException {
        String sql = """
                INSERT INTO agent_sessions
                  (agent_id, parent_session_id, agent_slug, project_dir, cwd, model,
                   turn_count, tool_call_count, tool_names,
                   first_message, all_user_text,
                   first_seen_at, last_updated_at, message_count, scanned_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(agent_id) DO UPDATE SET
                  parent_session_id = excluded.parent_session_id,
                  agent_slug        = excluded.agent_slug,
                  project_dir       = excluded.project_dir,
                  cwd               = excluded.cwd,
                  model             = excluded.model,
                  turn_count        = excluded.turn_count,
                  tool_call_count   = excluded.tool_call_count,
                  tool_names        = excluded.tool_names,
                  first_message     = excluded.first_message,
                  all_user_text     = excluded.all_user_text,
                  first_seen_at     = excluded.first_seen_at,
                  last_updated_at   = excluded.last_updated_at,
                  message_count     = excluded.message_count,
                  scanned_at        = excluded.scanned_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1,  a.getAgentId());
            ps.setString(2,  a.getParentSessionId());
            ps.setString(3,  a.getAgentSlug());
            ps.setString(4,  a.getProjectDir());
            ps.setString(5,  a.getCwd());
            ps.setString(6,  a.getModel());
            ps.setInt(7,     a.getTurnCount());
            ps.setInt(8,     a.getToolCallCount());
            ps.setString(9,  toJson(a.getToolNames()));
            ps.setString(10, a.getFirstMessage());
            ps.setString(11, a.getAllUserText());
            ps.setString(12, a.getFirstSeenAt());
            ps.setString(13, a.getLastUpdatedAt());
            ps.setInt(14,    a.getMessageCount());
            ps.setString(15, a.getScannedAt());
            ps.executeUpdate();
        }
    }

    /** Return the scanned_at timestamp for an agent_id, or null if not indexed. */
    public String getScannedAt(String agentId) throws SQLException {
        String sql = "SELECT scanned_at FROM agent_sessions WHERE agent_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("scanned_at") : null;
            }
        }
    }

    /** Full-text search over agent session transcripts using FTS5. */
    public List<AgentSession> search(String query, int limit) throws SQLException {
        String sql = """
                SELECT a.agent_id, a.parent_session_id, a.agent_slug, a.project_dir,
                       a.cwd, a.model, a.turn_count, a.tool_call_count, a.tool_names,
                       a.first_message, a.all_user_text,
                       a.first_seen_at, a.last_updated_at, a.message_count
                FROM agent_sessions_fts f
                JOIN agent_sessions a ON f.agent_id = a.agent_id
                WHERE agent_sessions_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, toFtsQuery(query));
            ps.setInt(2, limit);
            return mapResults(ps.executeQuery());
        }
    }

    /** Full-text search, optionally filtered to a specific parent session. */
    public List<AgentSession> search(String query, String parentSessionId, int limit) throws SQLException {
        if (parentSessionId == null || parentSessionId.isBlank()) {
            return search(query, limit);
        }
        // Search FTS then filter by parent in application code (SQLite FTS5 does not support
        // JOINed WHERE clauses efficiently, so we fetch more and filter)
        String sql = """
                SELECT a.agent_id, a.parent_session_id, a.agent_slug, a.project_dir,
                       a.cwd, a.model, a.turn_count, a.tool_call_count, a.tool_names,
                       a.first_message, a.all_user_text,
                       a.first_seen_at, a.last_updated_at, a.message_count
                FROM agent_sessions_fts f
                JOIN agent_sessions a ON f.agent_id = a.agent_id
                WHERE agent_sessions_fts MATCH ?
                  AND a.parent_session_id = ?
                ORDER BY rank
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, toFtsQuery(query));
            ps.setString(2, parentSessionId);
            ps.setInt(3, limit);
            return mapResults(ps.executeQuery());
        }
    }

    /** List agent sessions for a given parent session, newest first. */
    public List<AgentSession> listByParent(String parentSessionId, int limit) throws SQLException {
        String sql = """
                SELECT agent_id, parent_session_id, agent_slug, project_dir,
                       cwd, model, turn_count, tool_call_count, tool_names,
                       first_message, all_user_text,
                       first_seen_at, last_updated_at, message_count
                FROM agent_sessions
                WHERE parent_session_id = ?
                ORDER BY first_seen_at DESC
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, parentSessionId);
            ps.setInt(2, limit);
            return mapResults(ps.executeQuery());
        }
    }

    /** List all agent sessions, optionally filtered by project. Newest first. */
    public List<AgentSession> list(String projectDir, int limit) throws SQLException {
        boolean filtered = projectDir != null && !projectDir.isBlank();
        String sql = filtered
                ? "SELECT agent_id, parent_session_id, agent_slug, project_dir, cwd, model, turn_count, tool_call_count, tool_names, first_message, all_user_text, first_seen_at, last_updated_at, message_count FROM agent_sessions WHERE project_dir = ? ORDER BY first_seen_at DESC LIMIT ?"
                : "SELECT agent_id, parent_session_id, agent_slug, project_dir, cwd, model, turn_count, tool_call_count, tool_names, first_message, all_user_text, first_seen_at, last_updated_at, message_count FROM agent_sessions ORDER BY first_seen_at DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (filtered) {
                ps.setString(1, projectDir);
                ps.setInt(2, limit);
            } else {
                ps.setInt(1, limit);
            }
            return mapResults(ps.executeQuery());
        }
    }

    /** Aggregate stats for agent sessions. */
    public Stats stats() throws SQLException {
        String sql = """
                SELECT
                  COUNT(*)                 AS total_agents,
                  SUM(turn_count)          AS total_turns,
                  SUM(tool_call_count)     AS total_tool_calls,
                  SUM(message_count)       AS total_messages,
                  COUNT(DISTINCT parent_session_id) AS parent_sessions
                FROM agent_sessions
                """;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return new Stats(
                        rs.getInt("total_agents"),
                        rs.getLong("total_turns"),
                        rs.getLong("total_tool_calls"),
                        rs.getLong("total_messages"),
                        rs.getInt("parent_sessions")
                );
            }
            return new Stats(0, 0, 0, 0, 0);
        }
    }

    private List<AgentSession> mapResults(ResultSet rs) throws SQLException {
        List<AgentSession> out = new ArrayList<>();
        while (rs.next()) {
            AgentSession a = new AgentSession();
            a.setAgentId(rs.getString("agent_id"));
            a.setParentSessionId(rs.getString("parent_session_id"));
            a.setAgentSlug(rs.getString("agent_slug"));
            a.setProjectDir(rs.getString("project_dir"));
            a.setCwd(rs.getString("cwd"));
            a.setModel(rs.getString("model"));
            a.setTurnCount(rs.getInt("turn_count"));
            a.setToolCallCount(rs.getInt("tool_call_count"));
            a.setToolNames(fromJson(rs.getString("tool_names")));
            a.setFirstMessage(rs.getString("first_message"));
            a.setAllUserText(rs.getString("all_user_text"));
            a.setFirstSeenAt(rs.getString("first_seen_at"));
            a.setLastUpdatedAt(rs.getString("last_updated_at"));
            a.setMessageCount(rs.getInt("message_count"));
            out.add(a);
        }
        return out;
    }

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

    /**
     * Quote each term so user input is treated as literal text instead of raw
     * FTS syntax. Matches the pattern in SessionStore.
     */
    private String toFtsQuery(String query) {
        if (query == null || query.isBlank()) return "\"\"";
        StringBuilder out = new StringBuilder();
        for (String token : query.trim().split("\\s+")) {
            if (token.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append('"')
               .append(token.replace("\"", "\"\""))
               .append('"');
        }
        return out.isEmpty() ? "\"\"" : out.toString();
    }

    /** Aggregate statistics for agent sessions. */
    public record Stats(
            int totalAgents,
            long totalTurns,
            long totalToolCalls,
            long totalMessages,
            int parentSessions
    ) {}
}
