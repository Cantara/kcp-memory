package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.ManifestQualityRecord;

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
