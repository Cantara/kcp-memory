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
        // Every memory carries provenance. Derive one from the session's own
        // origin when the caller supplies none; on conflict we keep any
        // provenance already recorded so re-scans never clobber it.
        String provenance = s.getProvenance() != null && !s.getProvenance().isBlank()
                ? s.getProvenance() : deriveProvenance(s);
        String sql = """
                INSERT INTO sessions
                  (session_id, project_dir, git_branch, slug, model,
                   started_at, ended_at, turn_count, tool_call_count,
                   tool_names, files_json, first_message, all_user_text, scanned_at,
                   provenance, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
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
                  scanned_at      = excluded.scanned_at,
                  provenance      = COALESCE(sessions.provenance, excluded.provenance),
                  updated_at      = excluded.updated_at
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
            ps.setString(15, provenance);
            ps.setString(16, stamp());
            ps.executeUpdate();
        }
    }

    private static final java.time.format.DateTimeFormatter STAMP_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(java.time.ZoneOffset.UTC);

    /**
     * The canonical form of {@code sessions.updated_at}: ISO-8601 UTC with exactly three
     * fractional digits ({@link java.time.Instant#toString()} drops them when they are zero),
     * so plain string comparison orders correctly. SQL writers use
     * {@code strftime('%Y-%m-%dT%H:%M:%fZ','now')}, which gives the same shape.
     */
    public static String stamp() {
        return STAMP_FORMAT.format(java.time.Instant.now());
    }

    /**
     * A session that came from a peer is a summary (turn and tool counts, no tool calls), and
     * it used to keep the counts it had when it was first seen. Raise the counts to what the
     * peer reports now. Counts of a session only grow, so the larger value wins: a stale or
     * echoed copy can never lower them, and a session this machine indexed from its own
     * transcript keeps its counts unless a peer holds a later version of the same session.
     * Nothing else on the row changes.
     *
     * <p>There is deliberately no check on {@code source_instance}: a sender labels its own
     * sessions {@code 'local'}, so on the receiving side those rows would look local too.
     *
     * @return true if the row changed
     */
    public boolean raiseCounts(String sessionId, int turnCount, int toolCallCount) throws SQLException {
        String sql = """
                UPDATE sessions
                   SET turn_count      = MAX(turn_count, ?1),
                       tool_call_count = MAX(tool_call_count, ?2),
                       updated_at      = ?4
                 WHERE session_id = ?3
                   AND (turn_count < ?1 OR tool_call_count < ?2)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, turnCount);
            ps.setInt(2, toolCallCount);
            ps.setString(3, sessionId);
            ps.setString(4, stamp());
            return ps.executeUpdate() > 0;
        }
    }

    /** One session in the change feed, with the origin tag the push payload carries. */
    public record Changed(SearchResult session, String sourceInstance) {}

    /**
     * A page of the change feed. {@code next} is the cursor to resume after the last row
     * of this page, or the cursor that was passed in when the page is empty.
     */
    public record ChangePage(List<Changed> sessions, String next) {}

    /**
     * Sessions that changed after {@code cursor}, oldest change first, for peer sync.
     * A cursor is {@code <updated_at>|<session_id>}; null or blank starts from the beginning.
     * Ordering and paging use (updated_at, session_id), so rows that were written within the
     * same millisecond are neither skipped nor repeated at a page boundary. A row that
     * changes again reappears later in the feed, so a consumer sees every change at least once;
     * the one gap is a second change to a row within the same millisecond as the page that
     * already carried it, which the next change to that row closes.
     *
     * @param recallGate true to skip expired and forgotten memories, as {@link #list} does
     */
    public ChangePage changesSince(String cursor, int limit, boolean recallGate) throws SQLException {
        String after = "";
        String afterId = "";
        boolean hasCursor = cursor != null && !cursor.isBlank();
        if (hasCursor) {
            int bar = cursor.indexOf('|');
            after = bar < 0 ? cursor : cursor.substring(0, bar);
            afterId = bar < 0 ? "" : cursor.substring(bar + 1);
        }
        String cols = "session_id, project_dir, git_branch, slug, model, started_at, ended_at, "
                + "turn_count, tool_call_count, first_message, provenance, valid_until, source_instance, "
                + "COALESCE(updated_at, '') AS upd";
        // Same test as RECALL_GATE, with a numbered parameter so it can sit beside ?1..?3.
        String gate = recallGate
                ? " s.forgotten_at IS NULL AND (s.valid_until IS NULL OR s.valid_until > ?4) AND "
                : " ";
        String sql = "SELECT " + cols + " FROM sessions s WHERE" + gate
                + "(COALESCE(updated_at, '') > ?1 OR (COALESCE(updated_at, '') = ?1 AND session_id > ?2)) "
                + "ORDER BY upd, session_id LIMIT ?3";
        List<Changed> out = new ArrayList<>();
        String next = hasCursor ? cursor : "";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, after);
            ps.setString(2, afterId);
            ps.setInt(3, limit);
            if (recallGate) ps.setString(4, nowIso());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SearchResult r = mapRow(rs);
                    out.add(new Changed(r, rs.getString("source_instance")));
                    next = rs.getString("upd") + "|" + r.getSessionId();
                }
            }
        }
        return new ChangePage(out, next);
    }

    /** Derive a provenance descriptor from a session's own origin. */
    private static String deriveProvenance(Session s) {
        return ProvenanceFormat.local(s.getSlug(), s.getProjectDir(), s.getSessionId());
    }

    /** Return full session detail by session_id, or null if not found. */
    public Session getById(String sessionId) throws SQLException {
        String sql = """
                SELECT session_id, project_dir, git_branch, slug, model,
                       started_at, ended_at, turn_count, tool_call_count,
                       tool_names, files_json, first_message, all_user_text
                FROM sessions WHERE session_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Session s = new Session();
                s.setSessionId(rs.getString("session_id"));
                s.setProjectDir(rs.getString("project_dir"));
                s.setGitBranch(rs.getString("git_branch"));
                s.setSlug(rs.getString("slug"));
                s.setModel(rs.getString("model"));
                s.setStartedAt(rs.getString("started_at"));
                s.setEndedAt(rs.getString("ended_at"));
                s.setTurnCount(rs.getInt("turn_count"));
                s.setToolCallCount(rs.getInt("tool_call_count"));
                s.setFirstMessage(rs.getString("first_message"));
                s.setAllUserText(rs.getString("all_user_text"));
                s.setToolNames(fromJson(rs.getString("tool_names")));
                s.setFiles(fromJson(rs.getString("files_json")));
                return s;
            }
        }
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

    /**
     * Return session by exact ID, falling back to a prefix match for the
     * 8-char short IDs that list/search formatters display.
     * Returns null if not found, or if the prefix matches multiple sessions (ambiguous).
     */
    public Session getByIdOrPrefix(String sessionId) throws SQLException {
        Session exact = getById(sessionId);
        if (exact != null) return exact;
        if (sessionId == null || sessionId.length() >= 36) return null;

        String sql = """
                SELECT session_id, project_dir, git_branch, slug, model,
                       started_at, ended_at, turn_count, tool_call_count,
                       tool_names, files_json, first_message, all_user_text
                FROM sessions WHERE session_id LIKE ?
                LIMIT 2
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId + "%");
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Session s = new Session();
                s.setSessionId(rs.getString("session_id"));
                s.setProjectDir(rs.getString("project_dir"));
                s.setGitBranch(rs.getString("git_branch"));
                s.setSlug(rs.getString("slug"));
                s.setModel(rs.getString("model"));
                s.setStartedAt(rs.getString("started_at"));
                s.setEndedAt(rs.getString("ended_at"));
                s.setTurnCount(rs.getInt("turn_count"));
                s.setToolCallCount(rs.getInt("tool_call_count"));
                s.setFirstMessage(rs.getString("first_message"));
                s.setAllUserText(rs.getString("all_user_text"));
                s.setToolNames(fromJson(rs.getString("tool_names")));
                s.setFiles(fromJson(rs.getString("files_json")));
                if (rs.next()) return null; // ambiguous prefix — multiple matches
                return s;
            }
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

    // ------------------------------------------------------------------
    // Governance recall gate
    // ------------------------------------------------------------------
    // SQL predicate that skips memories which have been forgotten (tombstoned)
    // or whose retention window has expired. ISO-8601 UTC strings compare
    // lexicographically, so valid_until > now is a correct temporal test.
    // The ?-parameter is bound to the current instant. Mirrors GovernanceGate.
    private static final String RECALL_GATE =
            " s.forgotten_at IS NULL AND (s.valid_until IS NULL OR s.valid_until > ?) ";

    /**
     * Full-text search using FTS5, gated by memory governance. Expired or
     * forgotten memories are skipped and never surfaced. Returns up to limit
     * live results, each carrying its provenance.
     */
    public List<SearchResult> search(String query, int limit) throws SQLException {
        String sql = """
                SELECT s.session_id, s.project_dir, s.git_branch, s.slug, s.model,
                       s.started_at, s.ended_at, s.turn_count, s.tool_call_count,
                       s.first_message, s.provenance, s.valid_until, rank
                FROM sessions_fts
                JOIN sessions s ON sessions_fts.session_id = s.session_id
                WHERE sessions_fts MATCH ? AND
                """ + RECALL_GATE + """
                ORDER BY rank
                LIMIT ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, toFtsQuery(query));
            ps.setString(2, nowIso());
            ps.setInt(3, limit);
            return mapResults(ps.executeQuery());
        }
    }

    /**
     * List sessions, optionally filtered by project dir. Most recent first.
     * Gated by memory governance — expired or forgotten memories are skipped.
     */
    public List<SearchResult> list(String projectDir, int limit) throws SQLException {
        boolean filtered = projectDir != null && !projectDir.isBlank();
        String cols = "session_id, project_dir, git_branch, slug, model, started_at, ended_at, "
                + "turn_count, tool_call_count, first_message, provenance, valid_until";
        String sql = filtered
                ? "SELECT " + cols + " FROM sessions s WHERE project_dir = ? AND" + RECALL_GATE
                  + "ORDER BY started_at DESC LIMIT ?"
                : "SELECT " + cols + " FROM sessions s WHERE" + RECALL_GATE
                  + "ORDER BY started_at DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            if (filtered) ps.setString(i++, projectDir);
            ps.setString(i++, nowIso());
            ps.setInt(i, limit);
            return mapResults(ps.executeQuery());
        }
    }

    private static String nowIso() {
        return java.time.Instant.now().toString();
    }

    /** Aggregate stats. */
    public Stats stats() throws SQLException {
        String sql = """
                SELECT
                  COUNT(*)          AS total_sessions,
                  SUM(turn_count)   AS total_turns,
                  SUM(tool_call_count) AS total_tool_calls,
                  MIN(started_at)   AS oldest,
                  MAX(started_at)   AS newest,
                  MAX(scanned_at)   AS last_scanned_at
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
                        rs.getString("newest"),
                        rs.getString("last_scanned_at")
                );
            }
            return new Stats(0, 0, 0, null, null, null);
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
        while (rs.next()) out.add(mapRow(rs));
        return out;
    }

    private SearchResult mapRow(ResultSet rs) throws SQLException {
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
        r.setProvenance(rs.getString("provenance"));
        r.setValidUntil(rs.getString("valid_until"));
        try { r.setRank(rs.getDouble("rank")); } catch (SQLException ignored) {}
        return r;
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
     * FTS syntax. This avoids errors on characters like '-' in "queue-secret".
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

    // ------------------------------------------------------------------
    // Governance write API: retention & right-to-forget
    // ------------------------------------------------------------------

    /**
     * Declare (or clear) the retention window for a memory. Pass null to remove
     * any expiry. Returns true if the session exists and was updated.
     *
     * @throws IllegalArgumentException if validUntil is non-null/blank and not a
     *         parseable ISO-8601 UTC instant. RECALL_GATE compares valid_until
     *         lexicographically in SQL rather than parsing it, so a malformed
     *         value must be rejected here — otherwise it could sort as "not yet
     *         expired" and be recalled forever instead of failing closed.
     */
    public boolean setRetention(String sessionId, String validUntil) throws SQLException {
        if (validUntil != null && !validUntil.isBlank()) {
            try {
                java.time.Instant.parse(validUntil.trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "valid_until must be an ISO-8601 UTC instant (e.g. 2026-12-31T00:00:00Z): " + validUntil);
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sessions SET valid_until = ? WHERE session_id = ?")) {
            ps.setString(1, validUntil);
            ps.setString(2, sessionId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Exercise the right-to-forget: tombstone a memory so recall can never
     * surface it again. The row is retained (not deleted) so the forget itself
     * is auditable via {@link #getGovernance(String)}. Returns true if updated.
     */
    public boolean forget(String sessionId, String reason) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE sessions SET forgotten_at = ?, forget_reason = ? WHERE session_id = ?")) {
            ps.setString(1, nowIso());
            ps.setString(2, reason);
            ps.setString(3, sessionId);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * Like {@link #getByIdOrPrefix}, but applies the same recall gate as
     * {@link #search} and {@link #list}: returns null if the resolved session has
     * been forgotten or its retention window has expired. Content-exposing tools
     * (session detail, session tree) must resolve through this instead of the raw
     * accessor, or a forgotten memory can be read back in full despite being
     * gated out of every other recall path. Governance-management operations
     * (forget, retention) intentionally keep using the raw accessor, since they
     * must be able to find and re-manage a session that is already governed away.
     */
    public Session getByIdOrPrefixIfAllowed(String sessionId) throws SQLException {
        Session s = getByIdOrPrefix(sessionId);
        if (s == null) return null;
        Governance g = getGovernance(s.getSessionId());
        if (g == null) return s;
        GovernanceGate.Decision d = GovernanceGate.evaluate(
                g.validUntil(), g.forgottenAt(), g.forgetReason(), java.time.Instant.now());
        return d.allowed() ? s : null;
    }

    /** Governance metadata for a single memory, or null if the session is unknown. */
    public Governance getGovernance(String sessionId) throws SQLException {
        String sql = """
                SELECT session_id, provenance, valid_until, forgotten_at, forget_reason
                FROM sessions WHERE session_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Governance(
                        rs.getString("session_id"),
                        rs.getString("provenance"),
                        rs.getString("valid_until"),
                        rs.getString("forgotten_at"),
                        rs.getString("forget_reason"));
            }
        }
    }

    /**
     * Audit the recall gate for a query: run FTS <em>without</em> the gate and
     * attach a {@link GovernanceGate.Decision} to every candidate, so an
     * operator can see which memories were surfaced and which were skipped and
     * why. This is the audit counterpart to {@link #search(String, int)}.
     */
    public List<RecallAudit> auditSearch(String query, int limit) throws SQLException {
        String sql = """
                SELECT s.session_id, s.first_message, s.provenance,
                       s.valid_until, s.forgotten_at, s.forget_reason, rank
                FROM sessions_fts
                JOIN sessions s ON sessions_fts.session_id = s.session_id
                WHERE sessions_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """;
        java.time.Instant now = java.time.Instant.now();
        List<RecallAudit> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, toFtsQuery(query));
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GovernanceGate.Decision d = GovernanceGate.evaluate(
                            rs.getString("valid_until"),
                            rs.getString("forgotten_at"),
                            rs.getString("forget_reason"),
                            now);
                    // The audit trail proves a candidate was gated out — it must never
                    // itself become a channel for reading content that governance denied.
                    String firstMessage = d.allowed() ? rs.getString("first_message") : null;
                    out.add(new RecallAudit(
                            rs.getString("session_id"),
                            firstMessage,
                            rs.getString("provenance"),
                            d.allowed(),
                            d.reason()));
                }
            }
        }
        return out;
    }

    /** Governance metadata for a memory entry. */
    public record Governance(
            String sessionId,
            String provenance,
            String validUntil,
            String forgottenAt,
            String forgetReason
    ) {}

    /** One audited recall candidate: whether the gate allowed it, and why not. */
    public record RecallAudit(
            String sessionId,
            String firstMessage,
            String provenance,
            boolean allowed,
            String reason
    ) {}

    /** Aggregate statistics across all sessions. */
    public record Stats(
            int totalSessions,
            long totalTurns,
            long totalToolCalls,
            String oldest,
            String newest,
            String lastScannedAt
    ) {}
}
