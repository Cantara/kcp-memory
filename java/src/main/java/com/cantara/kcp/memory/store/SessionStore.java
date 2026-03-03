package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.model.Session;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD operations for the sessions table.
 */
public class SessionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection conn;

    public SessionStore(MemoryDatabase db) {
        this.conn = db.getConnection();
    }

    /** Insert or update a session (upsert by session_id). */
    public void upsert(Session s) throws SQLException {
        String sql = """
                INSERT INTO sessions
                  (session_id, project_dir, git_branch, slug, model,
                   started_at, ended_at, turn_count, tool_call_count,
                   tool_names, files_json, first_message, all_user_text, scanned_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(session_id) DO UPDATE SET
                  project_dir     = excluded.project_dir,
                  git_branch      = excluded.git_branch,
                  slug            = excluded.slug,
                  model           = excluded.model,
                  started_at      = excluded.started_at,
                  ended_at        = excluded.ended_at,
                  turn_count      = excluded.turn_count,
                  tool_call_count = excluded.tool_call_count,
                  tool_names      = excluded.tool_names,
                  files_json      = excluded.files_json,
                  first_message   = excluded.first_message,
                  all_user_text   = excluded.all_user_text,
                  scanned_at      = excluded.scanned_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1,  s.getSessionId());
            ps.setString(2,  s.getProjectDir());
            ps.setString(3,  s.getGitBranch());
            ps.setString(4,  s.getSlug());
            ps.setString(5,  s.getModel());
            ps.setString(6,  s.getStartedAt());
            ps.setString(7,  s.getEndedAt());
            ps.setInt(8,     s.getTurnCount());
            ps.setInt(9,     s.getToolCallCount());
            ps.setString(10, toJson(s.getToolNames()));
            ps.setString(11, toJson(s.getFiles()));
            ps.setString(12, s.getFirstMessage());
            ps.setString(13, s.getAllUserText());
            ps.setString(14, s.getScannedAt());
            ps.executeUpdate();
        }
    }

    /** Return the scanned_at timestamp for a session_id, or null if not indexed. */
    public String getScannedAt(String sessionId) throws SQLException {
        String sql = "SELECT scanned_at FROM sessions WHERE session_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("scanned_at") : null;
            }
        }
    }

    /** Full-text search using FTS5. Returns up to limit results. */
    public List<SearchResult> search(String query, int limit) throws SQLException {
        String sql = """
                SELECT s.session_id, s.project_dir, s.git_branch, s.slug, s.model,
                       s.started_at, s.ended_at, s.turn_count, s.tool_call_count,
                       s.first_message, rank
                FROM sessions_fts
                JOIN sessions s ON sessions_fts.session_id = s.session_id
                WHERE sessions_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setInt(2, limit);
            return mapResults(ps.executeQuery());
        }
    }

    /** List sessions, optionally filtered by project dir. Most recent first. */
    public List<SearchResult> list(String projectDir, int limit) throws SQLException {
        boolean filtered = projectDir != null && !projectDir.isBlank();
        String sql = filtered
                ? "SELECT session_id, project_dir, git_branch, slug, model, started_at, ended_at, turn_count, tool_call_count, first_message FROM sessions WHERE project_dir = ? ORDER BY started_at DESC LIMIT ?"
                : "SELECT session_id, project_dir, git_branch, slug, model, started_at, ended_at, turn_count, tool_call_count, first_message FROM sessions ORDER BY started_at DESC LIMIT ?";
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

    /** Aggregate stats. */
    public Stats stats() throws SQLException {
        String sql = """
                SELECT
                  COUNT(*)          AS total_sessions,
                  SUM(turn_count)   AS total_turns,
                  SUM(tool_call_count) AS total_tool_calls,
                  MIN(started_at)   AS oldest,
                  MAX(started_at)   AS newest
                FROM sessions
                """;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return new Stats(
                        rs.getInt("total_sessions"),
                        rs.getLong("total_turns"),
                        rs.getLong("total_tool_calls"),
                        rs.getString("oldest"),
                        rs.getString("newest")
                );
            }
            return new Stats(0, 0, 0, null, null);
        }
    }

    /** Number of sessions indexed for a given project dir. */
    public int countForProject(String projectDir) throws SQLException {
        String sql = "SELECT COUNT(*) FROM sessions WHERE project_dir = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectDir);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private List<SearchResult> mapResults(ResultSet rs) throws SQLException {
        List<SearchResult> out = new ArrayList<>();
        while (rs.next()) {
            SearchResult r = new SearchResult();
            r.setSessionId(rs.getString("session_id"));
            r.setProjectDir(rs.getString("project_dir"));
            r.setGitBranch(rs.getString("git_branch"));
            r.setSlug(rs.getString("slug"));
            r.setModel(rs.getString("model"));
            r.setStartedAt(rs.getString("started_at"));
            r.setEndedAt(rs.getString("ended_at"));
            r.setTurnCount(rs.getInt("turn_count"));
            r.setToolCallCount(rs.getInt("tool_call_count"));
            r.setFirstMessage(rs.getString("first_message"));
            try { r.setRank(rs.getDouble("rank")); } catch (SQLException ignored) {}
            out.add(r);
        }
        return out;
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** Aggregate statistics across all sessions. */
    public record Stats(
            int totalSessions,
            long totalTurns,
            long totalToolCalls,
            String oldest,
            String newest
    ) {}
}
