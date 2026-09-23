package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.Decision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        upsert(d, d.projectPath() + "/.sdd/decisions/index.yaml");
    }

    /**
     * Insert or update a decision, recording the YAML file it was read from.
     */
    public void upsert(Decision d, String filePath) throws SQLException {
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
            ps.setString(11, filePath);
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

    // ------------------------------------------------------------------
    // Checkout-collapsed queries
    // ------------------------------------------------------------------

    /**
     * A decision hit, collapsed across checkouts of the same repository.
     *
     * @param decision      the representative copy (preferred checkout, see {@link #checkoutRank})
     * @param otherProjects project paths of the other checkouts holding an identical record
     */
    public record DecisionHit(Decision decision, List<String> otherProjects) {
        public DecisionHit {
            otherProjects = List.copyOf(otherProjects);
        }
    }

    /**
     * Full-text search, collapsing identical records that exist in several checkouts of the same
     * repository (clones, git worktrees, .claude/worktrees/*) into one hit.
     * Records are identical when {@code decision_id}, {@code what} and {@code why} all match;
     * an edited version of the same id on another branch stays a separate hit.
     *
     * @param type   optional type filter (null/blank = any)
     * @param domain optional domain filter (null/blank = any)
     */
    public List<DecisionHit> searchCollapsed(String query, String type, String domain, int limit)
            throws SQLException {
        List<Decision> raw = search(query, overfetch(limit)).stream()
                .filter(d -> type == null || type.isBlank() || type.equals(d.type()))
                .filter(d -> domain == null || domain.isBlank() || domain.equals(d.domain()))
                .toList();
        return collapse(raw, limit);
    }

    /** Type/domain filter with the same checkout collapsing as {@link #searchCollapsed}. */
    public List<DecisionHit> filterCollapsed(String type, String domain, int limit) throws SQLException {
        return collapse(filter(type, domain, overfetch(limit)), limit);
    }

    /** Duplicates can outnumber distinct records many times over — fetch enough to fill {@code limit}. */
    private static int overfetch(int limit) {
        return Math.max(limit * 50, 500);
    }

    /**
     * Collapse identical records, keeping the order of each group's best-ranked member and using
     * the preferred checkout as the representative.
     */
    public static List<DecisionHit> collapse(List<Decision> ranked, int limit) {
        Map<List<String>, List<Decision>> groups = new LinkedHashMap<>();
        for (Decision d : ranked) {
            groups.computeIfAbsent(List.of(d.id(), d.what(), d.why()), k -> new ArrayList<>()).add(d);
        }
        List<DecisionHit> hits = new ArrayList<>();
        for (List<Decision> group : groups.values()) {
            if (hits.size() >= limit) break;
            List<Decision> sorted = new ArrayList<>(group);
            sorted.sort(Comparator.comparingInt((Decision d) -> checkoutRank(d.projectPath()))
                    .thenComparingInt(d -> d.projectPath().length())
                    .thenComparing(Decision::projectPath));
            List<String> others = new ArrayList<>();
            for (Decision d : sorted.subList(1, sorted.size())) {
                if (!others.contains(d.projectPath())) others.add(d.projectPath());
            }
            hits.add(new DecisionHit(sorted.get(0), others));
        }
        return hits;
    }

    /**
     * Lower is more canonical: 0 = main checkout, +1 = linked git worktree ({@code .git} is a file),
     * +2 = under a {@code .claude/worktrees/} directory.
     */
    static int checkoutRank(String projectPath) {
        int rank = 0;
        if (projectPath.replace('\\', '/').contains("/.claude/worktrees/")) rank += 2;
        try {
            if (Files.isRegularFile(Path.of(projectPath, ".git"))) rank += 1;
        } catch (InvalidPathException ignored) {
            // leave rank as is
        }
        return rank;
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
