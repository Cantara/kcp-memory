package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.Decision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD operations for the decisions table.
 */
public class DecisionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection conn;

    public DecisionStore(MemoryDatabase db) {
        this.conn = db.getConnection();
    }

    /**
     * Insert or update a decision (upsert by decision_id + project_path).
     */
    public void upsert(Decision d) throws SQLException {
        String sql = """
                INSERT INTO decisions
                  (decision_id, type, domain, what, why, alternatives, learned, updated,
                   tags, project_path, file_path, scanned_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,datetime('now'))
                ON CONFLICT(decision_id, project_path) DO UPDATE SET
                  type         = excluded.type,
                  domain       = excluded.domain,
                  what         = excluded.what,
                  why          = excluded.why,
                  alternatives = excluded.alternatives,
                  learned      = excluded.learned,
                  updated      = excluded.updated,
                  tags         = excluded.tags,
                  file_path    = excluded.file_path,
                  scanned_at   = excluded.scanned_at
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, d.id());
            ps.setString(2, d.type());
            ps.setString(3, d.domain());
            ps.setString(4, d.what());
            ps.setString(5, d.why());
            ps.setString(6, toJson(d.alternatives()));
            ps.setString(7, d.learned());
            ps.setString(8, d.updated());
            ps.setString(9, toJson(d.tags()));
            ps.setString(10, d.projectPath());
            ps.setString(11, d.projectPath() + "/.sdd/decisions/index.yaml");  // file_path
            ps.executeUpdate();
        }
    }

    /**
     * Search decisions using FTS5 full-text search.
     * Searches across what, why, tags, type, and domain fields.
     *
     * @param query FTS5 query string (e.g. "Lambda deployment", "video AND codec")
     * @param limit max results
     * @return list of matching decisions, ordered by relevance
     */
    public List<Decision> search(String query, int limit) throws SQLException {
        String sql = """
                SELECT d.decision_id, d.type, d.domain, d.what, d.why,
                       d.alternatives, d.learned, d.updated, d.tags, d.project_path
                FROM decisions_fts fts
                JOIN decisions d ON fts.rowid = d.id
                WHERE decisions_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """;
        List<Decision> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, query);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Decision(
                            rs.getString("decision_id"),
                            rs.getString("type"),
                            rs.getString("domain"),
                            rs.getString("what"),
                            rs.getString("why"),
                            fromJsonList(rs.getString("alternatives")),
                            rs.getString("learned"),
                            rs.getString("updated"),
                            fromJsonList(rs.getString("tags")),
                            rs.getString("project_path")
                    ));
                }
            }
        }
        return results;
    }

    /**
     * Filter decisions by type and/or domain.
     *
     * @param type   optional type filter (decision, anti-pattern, constraint, workaround)
     * @param domain optional domain filter (deployment, testing, video-build, etc.)
     * @param limit  max results
     * @return list of matching decisions
     */
    public List<Decision> filter(String type, String domain, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT decision_id, type, domain, what, why, " +
                "alternatives, learned, updated, tags, project_path FROM decisions WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (type != null && !type.isBlank()) {
            sql.append(" AND type = ?");
            params.add(type);
        }
        if (domain != null && !domain.isBlank()) {
            sql.append(" AND domain = ?");
            params.add(domain);
        }
        sql.append(" ORDER BY scanned_at DESC LIMIT ?");
        params.add(limit);

        List<Decision> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(new Decision(
                            rs.getString("decision_id"),
                            rs.getString("type"),
                            rs.getString("domain"),
                            rs.getString("what"),
                            rs.getString("why"),
                            fromJsonList(rs.getString("alternatives")),
                            rs.getString("learned"),
                            rs.getString("updated"),
                            fromJsonList(rs.getString("tags")),
                            rs.getString("project_path")
                    ));
                }
            }
        }
        return results;
    }

    /**
     * Get total count of indexed decisions.
     */
    public int count() throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM decisions")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * Delete all decisions from a specific project (used when rescanning).
     */
    public void deleteByProject(String projectPath) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM decisions WHERE project_path = ?")) {
            ps.setString(1, projectPath);
            ps.executeUpdate();
        }
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
