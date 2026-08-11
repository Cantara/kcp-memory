package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.ManifestEditProposal;
import com.cantara.kcp.memory.model.ManifestQualityRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mines {@link ManifestQualityStore}'s quality signals into evidence-backed edit
 * proposals (kcp-memory#64) — structured, inert output for human review, not
 * automatic edits. Extends the existing "needs attention" heuristic (already in
 * AnalyzeCmd) with per-session evidence: how many DISTINCT sessions exhibited
 * each issue, and which ones — the "corrected in N of the last M sessions" shape
 * the issue asked for.
 * <p>
 * Performance note: the per-session retry/help counts are computed with ONE
 * aggregate query across all manifest keys each (same shape and cost as
 * {@link ManifestQualityStore}'s own retry/help/error SQL), not one query per
 * candidate manifest. An earlier version did the latter and, against a hot
 * non-skill key like {@code SUPPRESSED} (over half of all tool_events on a
 * real instance), a single per-candidate self-join took minutes. Only the
 * small per-candidate evidence-session-ID lookup still runs per manifest, and
 * {@link #isNonSkillKey} keeps degenerate keys like {@code SUPPRESSED} and
 * {@code FILTER:*} out of consideration entirely — they were never authored
 * skills, so there is nothing to propose editing.
 */
public class ManifestEditProposalStore {

    /**
     * Shared with ManifestQualityStore's error-signal heuristic — kept identical intentionally.
     * NOTE: single {@code %} here, not {@code %%} — this constant is substituted as a plain
     * *value* into another template's {@code %s} (see usages below), never itself passed
     * through {@code .formatted()} as the template string. {@code String.formatted} does not
     * recursively reprocess substituted argument text, so a doubled {@code %%} here would
     * survive into the final SQL literally as two percent signs and never match anything.
     */
    private static final String ERROR_SIGNAL_CLAUSE = """
            output_preview IS NOT NULL
            AND (
                LOWER(output_preview) LIKE 'error%'
                OR LOWER(output_preview) LIKE '%exception%'
                OR LOWER(output_preview) LIKE '%traceback%'
                OR LOWER(output_preview) LIKE '%failed%'
                OR LOWER(output_preview) LIKE '%command not found%'
                OR LOWER(output_preview) LIKE '%no such file%'
                OR LOWER(output_preview) LIKE '%exit code %'
                OR LOWER(output_preview) LIKE '%exited with%'
            )
            """;

    /** Same single-% note as {@link #ERROR_SIGNAL_CLAUSE} — substituted as a value, not a template. */
    private static final String HELP_FOLLOWUP_JOIN_CLAUSE = """
            e2.id > e1.id
            AND (e2.command LIKE '%--help%' OR e2.command LIKE '% -h %' OR e2.command LIKE '% -h')
            AND (julianday(e2.event_ts) - julianday(e1.event_ts)) * 86400 <= 300
            """;

    /**
     * Excludes {@link #isNonSkillKey} keys at the SQL level, not just from the final
     * Java-side candidate list. This matters a lot in practice: on a real instance,
     * {@code SUPPRESSED} alone was over half of all tool_events (74k of 134k rows),
     * and a retry self-join over just that one key took ~29 minutes. Filtering only
     * the *output* of an aggregate query that still scans those rows to compute it
     * doesn't help — the exclusion has to be in the WHERE clause so the planner can
     * use the manifest_key index to skip the rows entirely.
     */
    private static final String NON_SKILL_KEY_EXCLUSION = "manifest_key != 'SUPPRESSED' AND manifest_key NOT LIKE 'FILTER:%'";
    private static final String NON_SKILL_KEY_EXCLUSION_E1 = "e1.manifest_key != 'SUPPRESSED' AND e1.manifest_key NOT LIKE 'FILTER:%'";

    private final MemoryDatabase db;
    private final ManifestQualityStore qualityStore;

    public ManifestEditProposalStore(MemoryDatabase db) {
        this.db = db;
        this.qualityStore = new ManifestQualityStore(db);
    }

    /**
     * @param sinceDays      only consider events from the last N days
     * @param minCalls       exclude manifests with fewer than this many calls
     * @param scoreThreshold only propose manifests at or above this composite quality score
     *                       (same 0-1 scale as ManifestQualityStore, higher = worse)
     * @param evidenceLimit  max session IDs to attach as evidence per proposal
     * @return proposals ranked worst quality first
     */
    public List<ManifestEditProposal> proposeEdits(int sinceDays, int minCalls, double scoreThreshold, int evidenceLimit) throws SQLException {
        List<ManifestQualityRecord> candidates = qualityStore.analyze(sinceDays, minCalls, Integer.MAX_VALUE)
                .stream()
                .filter(r -> r.qualityScore() >= scoreThreshold)
                .filter(r -> !isNonSkillKey(r.manifestKey()))
                .toList();

        if (candidates.isEmpty()) return List.of();

        Connection conn = db.getConnection();
        String cutoff = sinceDays > 0
                ? "datetime('now', '-" + sinceDays + " days')"
                : "'1970-01-01T00:00:00Z'";
        String now = Instant.now().toString();

        // One aggregate pass per signal across ALL manifest keys — see class javadoc.
        Map<String, Integer> totalSessionsByKey = distinctSessionCounts(conn, cutoff);
        Map<String, Integer> retrySessionsByKey = distinctRetrySessionCounts(conn, cutoff);
        Map<String, Integer> helpSessionsByKey  = distinctHelpSessionCounts(conn, cutoff);
        Map<String, Integer> errorSessionsByKey = distinctErrorSessionCounts(conn, cutoff);

        List<ManifestEditProposal> proposals = new ArrayList<>();
        for (ManifestQualityRecord r : candidates) {
            String key = r.manifestKey();
            int totalSessions = totalSessionsByKey.getOrDefault(key, 0);
            int retrySessions = retrySessionsByKey.getOrDefault(key, 0);
            int helpSessions  = helpSessionsByKey.getOrDefault(key, 0);
            int errorSessions = errorSessionsByKey.getOrDefault(key, 0);

            String reason;
            int affected;
            String evidenceKind;
            if (retrySessions >= helpSessions && retrySessions >= errorSessions) {
                reason = "high retry rate (%.0f%% of calls)".formatted(r.retryRate() * 100);
                affected = retrySessions;
                evidenceKind = "retry";
            } else if (helpSessions >= errorSessions) {
                reason = "high --help follow-up rate (%.0f%% of calls)".formatted(r.helpFollowupRate() * 100);
                affected = helpSessions;
                evidenceKind = "help";
            } else {
                reason = "high error rate (%.0f%% of calls)".formatted(r.errorRate() * 100);
                affected = errorSessions;
                evidenceKind = "error";
            }

            // Only the small, per-candidate evidence-session-ID lookup runs per manifest —
            // candidates are already filtered to non-degenerate keys with modest call counts.
            List<String> evidence = evidenceSessionIds(conn, key, cutoff, evidenceKind, evidenceLimit);

            proposals.add(new ManifestEditProposal(
                    key, reason, r.qualityScore(),
                    r.totalCalls(), totalSessions, affected,
                    retrySessions, helpSessions, errorSessions,
                    evidence, now
            ));
        }

        proposals.sort((a, b) -> Double.compare(b.qualityScore(), a.qualityScore()));
        return proposals;
    }

    /**
     * Non-authored manifest keys with no skill/manifest YAML behind them — filter noise
     * and suppressed-command markers, not candidates for a human to review or edit.
     */
    private static boolean isNonSkillKey(String manifestKey) {
        return manifestKey == null
                || "SUPPRESSED".equals(manifestKey)
                || manifestKey.startsWith("FILTER:");
    }

    private Map<String, Integer> distinctSessionCounts(Connection conn, String cutoff) throws SQLException {
        String sql = """
                SELECT manifest_key, COUNT(DISTINCT session_id) AS n
                FROM tool_events
                WHERE manifest_key IS NOT NULL AND event_ts >= %s AND %s
                GROUP BY manifest_key
                """.formatted(cutoff, NON_SKILL_KEY_EXCLUSION);
        return queryCountMap(conn, sql, "manifest_key");
    }

    private Map<String, Integer> distinctRetrySessionCounts(Connection conn, String cutoff) throws SQLException {
        String sql = """
                SELECT e1.manifest_key AS mk, COUNT(DISTINCT e1.session_id) AS n
                FROM tool_events e1
                JOIN tool_events e2 ON e1.manifest_key = e2.manifest_key
                    AND e1.session_id = e2.session_id AND e2.id > e1.id
                    AND (julianday(e2.event_ts) - julianday(e1.event_ts)) * 86400 <= 90
                WHERE e1.manifest_key IS NOT NULL AND e1.event_ts >= %s AND %s
                GROUP BY e1.manifest_key
                """.formatted(cutoff, NON_SKILL_KEY_EXCLUSION_E1);
        return queryCountMap(conn, sql, "mk");
    }

    private Map<String, Integer> distinctHelpSessionCounts(Connection conn, String cutoff) throws SQLException {
        String sql = """
                SELECT e1.manifest_key AS mk, COUNT(DISTINCT e1.session_id) AS n
                FROM tool_events e1
                JOIN tool_events e2 ON e1.session_id = e2.session_id AND %s
                WHERE e1.manifest_key IS NOT NULL AND e1.event_ts >= %s AND %s
                GROUP BY e1.manifest_key
                """.formatted(HELP_FOLLOWUP_JOIN_CLAUSE, cutoff, NON_SKILL_KEY_EXCLUSION_E1);
        return queryCountMap(conn, sql, "mk");
    }

    private Map<String, Integer> distinctErrorSessionCounts(Connection conn, String cutoff) throws SQLException {
        String sql = """
                SELECT manifest_key, COUNT(DISTINCT session_id) AS n
                FROM tool_events
                WHERE manifest_key IS NOT NULL AND event_ts >= %s AND %s AND %s
                GROUP BY manifest_key
                """.formatted(cutoff, ERROR_SIGNAL_CLAUSE, NON_SKILL_KEY_EXCLUSION);
        return queryCountMap(conn, sql, "manifest_key");
    }

    private Map<String, Integer> queryCountMap(Connection conn, String sql, String keyColumn) throws SQLException {
        Map<String, Integer> map = new LinkedHashMap<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                map.put(rs.getString(keyColumn), rs.getInt("n"));
            }
        }
        return map;
    }

    private List<String> evidenceSessionIds(Connection conn, String manifestKey, String cutoff, String kind, int limit) throws SQLException {
        String sql = switch (kind) {
            case "retry" -> """
                    SELECT e1.session_id AS sid, MAX(e1.event_ts) AS ts
                    FROM tool_events e1
                    JOIN tool_events e2 ON e1.manifest_key = e2.manifest_key
                        AND e1.session_id = e2.session_id AND e2.id > e1.id
                        AND (julianday(e2.event_ts) - julianday(e1.event_ts)) * 86400 <= 90
                    WHERE e1.manifest_key = ? AND e1.event_ts >= %s
                    GROUP BY e1.session_id ORDER BY ts DESC LIMIT ?
                    """.formatted(cutoff);
            case "help" -> """
                    SELECT e1.session_id AS sid, MAX(e1.event_ts) AS ts
                    FROM tool_events e1
                    JOIN tool_events e2 ON e1.session_id = e2.session_id AND %s
                    WHERE e1.manifest_key = ? AND e1.event_ts >= %s
                    GROUP BY e1.session_id ORDER BY ts DESC LIMIT ?
                    """.formatted(HELP_FOLLOWUP_JOIN_CLAUSE, cutoff);
            default -> """
                    SELECT session_id AS sid, MAX(event_ts) AS ts FROM tool_events
                    WHERE manifest_key = ? AND event_ts >= %s AND %s
                    GROUP BY session_id ORDER BY ts DESC LIMIT ?
                    """.formatted(cutoff, ERROR_SIGNAL_CLAUSE);
        };
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, manifestKey);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString("sid"));
            }
        }
        return ids;
    }
}
