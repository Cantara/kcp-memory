package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.cantara.kcp.memory.store.ToolUsageStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /stats — aggregate statistics across all indexed sessions.
 * Also reads KCP bridge usage from ~/.kcp/usage.db if present (RFC-0017).
 */
public class StatsHandler extends BaseHandler {

    private static final Path KCP_USAGE_DB = Path.of(
        System.getProperty("user.home"), ".kcp", "usage.db");

    private final SessionStore sessionStore;
    private final ToolUsageStore toolUsageStore;

    public StatsHandler(MemoryDatabase db) {
        this.sessionStore   = new SessionStore(db);
        this.toolUsageStore = new ToolUsageStore(db);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }
        try {
            SessionStore.Stats stats = sessionStore.stats();
            var topTools = toolUsageStore.topTools(10);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("totalSessions",   stats.totalSessions());
            body.put("totalTurns",      stats.totalTurns());
            body.put("totalToolCalls",  stats.totalToolCalls());
            body.put("oldestSession",   stats.oldest());
            body.put("newestSession",   stats.newest());
            body.put("topTools",        topTools);

            // RFC-0017: include KCP bridge usage stats if usage.db exists
            Map<String, Object> kcpUsage = readKcpUsage();
            if (kcpUsage != null) {
                body.put("kcpBridgeUsage", kcpUsage);
            }

            sendJson(ex, 200, body);
        } catch (Exception e) {
            sendError(ex, 500, "Stats failed: " + e.getMessage());
        }
    }

    /**
     * Reads aggregate KCP bridge usage from ~/.kcp/usage.db (read-only).
     * Returns null if the file does not exist or cannot be read.
     */
    private Map<String, Object> readKcpUsage() {
        if (!Files.exists(KCP_USAGE_DB)) return null;
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + KCP_USAGE_DB);
             Statement st = conn.createStatement()) {

            Map<String, Object> result = new LinkedHashMap<>();

            // Total counts
            try (ResultSet rs = st.executeQuery(
                "SELECT COUNT(CASE WHEN event_type='search' THEN 1 END) AS searches," +
                "       COUNT(CASE WHEN event_type='get_unit' THEN 1 END) AS gets" +
                "  FROM usage_events")) {
                if (rs.next()) {
                    result.put("totalSearches", rs.getLong("searches"));
                    result.put("totalGets",     rs.getLong("gets"));
                }
            }

            // Tokens saved
            try (ResultSet rs = st.executeQuery(
                "SELECT COALESCE(SUM(manifest_token_total - token_estimate), 0) AS saved" +
                "  FROM usage_events" +
                " WHERE event_type='get_unit'" +
                "   AND token_estimate IS NOT NULL" +
                "   AND manifest_token_total IS NOT NULL")) {
                if (rs.next()) {
                    result.put("tokensSaved", rs.getLong("saved"));
                }
            }

            // Top 5 units
            List<Map<String, Object>> topUnits = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                "SELECT unit_id, COUNT(*) AS cnt" +
                "  FROM usage_events WHERE event_type='get_unit' AND unit_id IS NOT NULL" +
                "  GROUP BY unit_id ORDER BY cnt DESC LIMIT 5")) {
                while (rs.next()) {
                    topUnits.add(Map.of(
                        "unitId", rs.getString("unit_id"),
                        "count",  rs.getLong("cnt")));
                }
            }
            result.put("topUnits", topUnits);

            return result;
        } catch (Exception e) {
            return null; // silently skip on any error
        }
    }
}
