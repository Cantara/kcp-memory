package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.ManifestQualityRecord;
import com.cantara.kcp.memory.model.ManifestVersionRecord;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Analytics queries over tool_events to compute manifest quality metrics.
 * <p>
 * Measures how well each KCP manifest serves the agent by tracking:
 * <ul>
 *     <li>Retry rate — same manifest invoked again within 90 seconds (first call likely failed)</li>
 *     <li>Help-followup rate — agent looked up --help within 5 minutes (manifest wasn't complete enough)</li>
 *     <li>Error rate — output contained error signals</li>
 * </ul>
 */
public class ManifestQualityStore {

    private final MemoryDatabase db;

    public ManifestQualityStore(MemoryDatabase db) {
        this.db = db;
    }

    /**
     * Compute quality metrics for all manifest keys, ranked by quality score (worst first).
     *
     * @param sinceDays  only consider events from the last N days
     * @param minCalls   exclude manifests with fewer than this many calls
     * @param top        return at most this many results
     * @return ranked list of manifest quality records, worst quality first
     */
    public List<ManifestQualityRecord> analyze(int sinceDays, int minCalls, int top) throws SQLException {
        Connection conn = db.getConnection();

        String cutoff = sinceDays > 0
                ? "datetime('now', '-" + sinceDays + " days')"
                : "'1970-01-01T00:00:00Z'";

        // Step 1: total calls per manifest
        Map<String, Integer> totalCalls = new LinkedHashMap<>();
        String totalSql = """
                SELECT manifest_key, COUNT(*) AS cnt
                FROM tool_events
                WHERE manifest_key IS NOT NULL
                  AND event_ts >= %s
                GROUP BY manifest_key
                HAVING cnt >= ?
                """.formatted(cutoff);

        try (PreparedStatement ps = conn.prepareStatement(totalSql)) {
            ps.setInt(1, minCalls);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    totalCalls.put(rs.getString("manifest_key"), rs.getInt("cnt"));
                }
            }
        }

        if (totalCalls.isEmpty()) return List.of();

        // Step 2: retry counts — same manifest_key fired again within 90 seconds in same session
        Map<String, Integer> retryCounts = new LinkedHashMap<>();
        String retrySql = """
                SELECT e1.manifest_key, COUNT(DISTINCT e1.id) AS retry_count
                FROM tool_events e1
                JOIN tool_events e2 ON
                    e1.manifest_key = e2.manifest_key
                    AND e1.session_id = e2.session_id
                    AND e2.id > e1.id
                    AND (julianday(e2.event_ts) - julianday(e1.event_ts)) * 86400 <= 90
                WHERE e1.manifest_key IS NOT NULL
                  AND e1.event_ts >= %s
                GROUP BY e1.manifest_key
                """.formatted(cutoff);

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(retrySql)) {
            while (rs.next()) {
                retryCounts.put(rs.getString("manifest_key"), rs.getInt("retry_count"));
            }
        }

        // Step 3: help-followup counts — agent ran --help or -h within 5 minutes after this manifest
        Map<String, Integer> helpCounts = new LinkedHashMap<>();
        String helpSql = """
                SELECT e1.manifest_key, COUNT(DISTINCT e1.id) AS help_followup_count
                FROM tool_events e1
                JOIN tool_events e2 ON
                    e1.session_id = e2.session_id
                    AND e2.id > e1.id
                    AND (e2.command LIKE '%%--help%%' OR e2.command LIKE '%% -h %%' OR e2.command LIKE '%% -h')
                    AND (julianday(e2.event_ts) - julianday(e1.event_ts)) * 86400 <= 300
                WHERE e1.manifest_key IS NOT NULL
                  AND e1.event_ts >= %s
                GROUP BY e1.manifest_key
                """.formatted(cutoff);

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(helpSql)) {
            while (rs.next()) {
                helpCounts.put(rs.getString("manifest_key"), rs.getInt("help_followup_count"));
            }
        }

        // Step 4: error counts — output_preview contains error signals
        Map<String, Integer> errorCounts = new LinkedHashMap<>();
        String errorSql = """
                SELECT manifest_key, COUNT(*) AS error_count
                FROM tool_events
                WHERE manifest_key IS NOT NULL
                  AND event_ts >= %s
                  AND output_preview IS NOT NULL
                  AND (
                      LOWER(output_preview) LIKE 'error%%'
                      OR LOWER(output_preview) LIKE '%%exception%%'
                      OR LOWER(output_preview) LIKE '%%traceback%%'
                      OR LOWER(output_preview) LIKE '%%failed%%'
                      OR LOWER(output_preview) LIKE '%%command not found%%'
                      OR LOWER(output_preview) LIKE '%%no such file%%'
                      OR LOWER(output_preview) LIKE '%%exit code %%'
                      OR LOWER(output_preview) LIKE '%%exited with%%'
                  )
                GROUP BY manifest_key
                """.formatted(cutoff);

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(errorSql)) {
            while (rs.next()) {
                errorCounts.put(rs.getString("manifest_key"), rs.getInt("error_count"));
            }
        }

        // Step 5: assemble records and compute quality score
        List<ManifestQualityRecord> records = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : totalCalls.entrySet()) {
            String key   = entry.getKey();
            int    total = entry.getValue();
            int    retries   = retryCounts.getOrDefault(key, 0);
            int    helps     = helpCounts.getOrDefault(key, 0);
            int    errors    = errorCounts.getOrDefault(key, 0);

            double retryRate = (double) retries / total;
            double helpRate  = (double) helps / total;
            double errorRate = (double) errors / total;
            double score     = retryRate * 0.4 + helpRate * 0.4 + errorRate * 0.2;

            records.add(new ManifestQualityRecord(key, total, retries, helps, errors, score));
        }

        // Sort by quality score descending (worst first), limit to top N
        records.sort((a, b) -> Double.compare(b.qualityScore(), a.qualityScore()));
        if (records.size() > top) {
            records = records.subList(0, top);
        }

        return records;
    }

    /**
     * Compute quality metrics grouped by (manifest_key, manifest_version), sorted by
     * manifest_key asc then first_seen asc. Enables before/after comparison when a
     * manifest is updated.
     *
     * @param sinceDays only consider events from the last N days
     * @param minCalls  exclude (key, version) pairs with fewer than this many calls
     * @return list of per-version records
     */
    public List<ManifestVersionRecord> analyzeByVersion(int sinceDays, int minCalls) throws SQLException {
        Connection conn = db.getConnection();

        String cutoff = sinceDays > 0
                ? "datetime('now', '-" + sinceDays + " days')"
                : "'1970-01-01T00:00:00Z'";

        record VersionKey(String mk, String mv) {}

        // Step 1: total calls + date range per (manifest_key, manifest_version)
        Map<VersionKey, Integer> totalCalls = new LinkedHashMap<>();
        Map<VersionKey, String>  firstSeen  = new LinkedHashMap<>();
        Map<VersionKey, String>  lastSeen   = new LinkedHashMap<>();

        String totalSql = """
                SELECT manifest_key, COALESCE(manifest_version, 'unknown') AS mv,
                       COUNT(*) AS cnt,
                       MIN(DATE(event_ts)) AS first_seen, MAX(DATE(event_ts)) AS last_seen
                FROM tool_events
                WHERE manifest_key IS NOT NULL
                  AND event_ts >= %s
                GROUP BY manifest_key, mv
                HAVING cnt >= ?
                ORDER BY manifest_key, first_seen
                """.formatted(cutoff);

        try (PreparedStatement ps = conn.prepareStatement(totalSql)) {
            ps.setInt(1, minCalls);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    VersionKey k = new VersionKey(rs.getString("manifest_key"), rs.getString("mv"));
                    totalCalls.put(k, rs.getInt("cnt"));
                    firstSeen.put(k, rs.getString("first_seen"));
                    lastSeen.put(k, rs.getString("last_seen"));
                }
            }
        }

        if (totalCalls.isEmpty()) return List.of();

        // Step 2: retry counts — same (manifest_key, manifest_version) within 90s in same session
        Map<VersionKey, Integer> retryCounts = new LinkedHashMap<>();
        String retrySql = """
                SELECT e1.manifest_key, COALESCE(e1.manifest_version, 'unknown') AS mv,
                       COUNT(DISTINCT e1.id) AS retry_count
                FROM tool_events e1
                JOIN tool_events e2 ON
                    e1.manifest_key = e2.manifest_key
                    AND COALESCE(e1.manifest_version, 'unknown') = COALESCE(e2.manifest_version, 'unknown')
                    AND e1.session_id = e2.session_id
                    AND e2.id > e1.id
                    AND (julianday(e2.event_ts) - julianday(e1.event_ts)) * 86400 <= 90
                WHERE e1.manifest_key IS NOT NULL
                  AND e1.event_ts >= %s
                GROUP BY e1.manifest_key, mv
                """.formatted(cutoff);

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(retrySql)) {
            while (rs.next()) {
                VersionKey k = new VersionKey(rs.getString("manifest_key"), rs.getString("mv"));
                retryCounts.put(k, rs.getInt("retry_count"));
            }
        }

        // Step 3: help-followup counts
        Map<VersionKey, Integer> helpCounts = new LinkedHashMap<>();
        String helpSql = """
                SELECT e1.manifest_key, COALESCE(e1.manifest_version, 'unknown') AS mv,
                       COUNT(DISTINCT e1.id) AS help_followup_count
                FROM tool_events e1
                JOIN tool_events e2 ON
                    e1.session_id = e2.session_id
                    AND e2.id > e1.id
                    AND (e2.command LIKE '%%--help%%' OR e2.command LIKE '%% -h %%' OR e2.command LIKE '%% -h')
                    AND (julianday(e2.event_ts) - julianday(e1.event_ts)) * 86400 <= 300
                WHERE e1.manifest_key IS NOT NULL
                  AND e1.event_ts >= %s
                GROUP BY e1.manifest_key, mv
                """.formatted(cutoff);

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(helpSql)) {
            while (rs.next()) {
                VersionKey k = new VersionKey(rs.getString("manifest_key"), rs.getString("mv"));
                helpCounts.put(k, rs.getInt("help_followup_count"));
            }
        }

        // Step 4: error counts
        Map<VersionKey, Integer> errorCounts = new LinkedHashMap<>();
        String errorSql = """
                SELECT manifest_key, COALESCE(manifest_version, 'unknown') AS mv, COUNT(*) AS error_count
                FROM tool_events
                WHERE manifest_key IS NOT NULL
                  AND event_ts >= %s
                  AND output_preview IS NOT NULL
                  AND (
                      LOWER(output_preview) LIKE 'error%%'
                      OR LOWER(output_preview) LIKE '%%exception%%'
                      OR LOWER(output_preview) LIKE '%%traceback%%'
                      OR LOWER(output_preview) LIKE '%%failed%%'
                      OR LOWER(output_preview) LIKE '%%command not found%%'
                      OR LOWER(output_preview) LIKE '%%no such file%%'
                      OR LOWER(output_preview) LIKE '%%exit code %%'
                      OR LOWER(output_preview) LIKE '%%exited with%%'
                  )
                GROUP BY manifest_key, mv
                """.formatted(cutoff);

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(errorSql)) {
            while (rs.next()) {
                VersionKey k = new VersionKey(rs.getString("manifest_key"), rs.getString("mv"));
                errorCounts.put(k, rs.getInt("error_count"));
            }
        }

        // Step 5: assemble records (order preserved from totalCalls LinkedHashMap)
        List<ManifestVersionRecord> records = new ArrayList<>();
        for (Map.Entry<VersionKey, Integer> entry : totalCalls.entrySet()) {
            VersionKey k     = entry.getKey();
            int        total = entry.getValue();
            int        retries = retryCounts.getOrDefault(k, 0);
            int        helps   = helpCounts.getOrDefault(k, 0);
            int        errors  = errorCounts.getOrDefault(k, 0);

            double retryRate = (double) retries / total;
            double helpRate  = (double) helps / total;
            double errorRate = (double) errors / total;
            double score     = retryRate * 0.4 + helpRate * 0.4 + errorRate * 0.2;

            records.add(new ManifestVersionRecord(
                    k.mk(), k.mv(),
                    firstSeen.get(k), lastSeen.get(k),
                    total, retries, helps, errors, score));
        }

        return records;
    }

    /** Total number of distinct manifest keys in the events table. */
    public int countManifests() throws SQLException {
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(DISTINCT manifest_key) FROM tool_events WHERE manifest_key IS NOT NULL")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /** Total events with a manifest key. */
    public long countManifestCalls() throws SQLException {
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM tool_events WHERE manifest_key IS NOT NULL")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }
}
