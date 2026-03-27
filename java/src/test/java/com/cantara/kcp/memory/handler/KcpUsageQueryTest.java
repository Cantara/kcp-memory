package com.cantara.kcp.memory.handler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the KCP usage DB query logic used by StatsHandler.readKcpUsage().
 *
 * Since readKcpUsage() is a private method with a hardcoded path (private static final),
 * and we must not modify production code, this test validates the identical SQL queries
 * against a temp SQLite DB. Any drift between these queries and StatsHandler is caught
 * in code review — and these tests directly cover the silent-failure scenario where
 * the query logic was untested.
 */
class KcpUsageQueryTest {

    private Path tempDb;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-usage-test-", ".db");
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {
            st.executeUpdate("PRAGMA journal_mode=WAL");
            st.executeUpdate("""
                CREATE TABLE usage_events (
                    id                   INTEGER PRIMARY KEY AUTOINCREMENT,
                    timestamp            TEXT    NOT NULL,
                    event_type           TEXT    NOT NULL,
                    project              TEXT,
                    query                TEXT,
                    unit_id              TEXT,
                    result_count         INTEGER,
                    token_estimate       INTEGER,
                    manifest_token_total INTEGER,
                    session_id           TEXT
                )""");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempDb);
    }

    @Test
    void countsSearchAndGetEvents() throws Exception {
        insertEvent("search", "proj", "find auth", null, null, null);
        insertEvent("search", "proj", "find config", null, null, null);
        insertEvent("get_unit", "proj", null, "xorcery", 100, 5000);
        insertEvent("get_unit", "proj", null, "lib-pcb", 200, 4000);
        insertEvent("get_unit", "proj", null, "xorcery", 150, 5000);

        Map<String, Object> result = readKcpUsage();

        assertNotNull(result);
        assertEquals(2L, result.get("totalSearches"));
        assertEquals(3L, result.get("totalGets"));
    }

    @Test
    void calculatesTokensSaved() throws Exception {
        // saved = (5000-100) + (4000-200) + (5000-150) = 4900 + 3800 + 4850 = 13550
        insertEvent("get_unit", "proj", null, "xorcery", 100, 5000);
        insertEvent("get_unit", "proj", null, "lib-pcb", 200, 4000);
        insertEvent("get_unit", "proj", null, "xorcery", 150, 5000);

        Map<String, Object> result = readKcpUsage();

        assertEquals(13550L, result.get("tokensSaved"));
    }

    @Test
    void tokensSavedIgnoresNulls() throws Exception {
        insertEvent("get_unit", "proj", null, "xorcery", 100, 5000);
        insertEvent("get_unit", "proj", null, "lib-pcb", null, null); // nulls should be excluded

        Map<String, Object> result = readKcpUsage();

        assertEquals(4900L, result.get("tokensSaved"));
    }

    @Test
    void returnsTopUnitsOrderedByCount() throws Exception {
        for (int i = 0; i < 5; i++) insertEvent("get_unit", "proj", null, "xorcery", 100, 2000);
        for (int i = 0; i < 3; i++) insertEvent("get_unit", "proj", null, "lib-pcb", 200, 4000);
        insertEvent("get_unit", "proj", null, "whydah", 50, 1000);

        Map<String, Object> result = readKcpUsage();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topUnits = (List<Map<String, Object>>) result.get("topUnits");
        assertNotNull(topUnits);
        assertEquals(3, topUnits.size());
        assertEquals("xorcery", topUnits.get(0).get("unitId"));
        assertEquals(5L, topUnits.get(0).get("count"));
        assertEquals("lib-pcb", topUnits.get(1).get("unitId"));
        assertEquals(3L, topUnits.get(1).get("count"));
        assertEquals("whydah", topUnits.get(2).get("unitId"));
        assertEquals(1L, topUnits.get(2).get("count"));
    }

    @Test
    void emptyDbReturnsZeroCounts() throws Exception {
        Map<String, Object> result = readKcpUsage();

        assertNotNull(result);
        assertEquals(0L, result.get("totalSearches"));
        assertEquals(0L, result.get("totalGets"));
        assertEquals(0L, result.get("tokensSaved"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topUnits = (List<Map<String, Object>>) result.get("topUnits");
        assertTrue(topUnits.isEmpty());
    }

    @Test
    void topUnitsLimitedToFive() throws Exception {
        // Insert 7 distinct units
        for (String unit : List.of("a", "b", "c", "d", "e", "f", "g")) {
            insertEvent("get_unit", "proj", null, unit, 100, 2000);
        }

        Map<String, Object> result = readKcpUsage();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topUnits = (List<Map<String, Object>>) result.get("topUnits");
        assertEquals(5, topUnits.size());
    }

    // --- Helpers: replicate the exact queries from StatsHandler.readKcpUsage() ---

    /**
     * Executes the identical SQL queries from StatsHandler.readKcpUsage()
     * against the temp DB. This is a faithful copy of the production query logic.
     */
    private Map<String, Object> readKcpUsage() throws Exception {
        try (Connection conn = connect();
             Statement st = conn.createStatement()) {

            Map<String, Object> result = new LinkedHashMap<>();

            // Total counts — exact query from StatsHandler
            try (ResultSet rs = st.executeQuery(
                    "SELECT COUNT(CASE WHEN event_type='search' THEN 1 END) AS searches," +
                    "       COUNT(CASE WHEN event_type='get_unit' THEN 1 END) AS gets" +
                    "  FROM usage_events")) {
                if (rs.next()) {
                    result.put("totalSearches", rs.getLong("searches"));
                    result.put("totalGets",     rs.getLong("gets"));
                }
            }

            // Tokens saved — exact query from StatsHandler
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

            // Top 5 units — exact query from StatsHandler
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
        }
    }

    private void insertEvent(String eventType, String project, String query,
                              String unitId, Integer tokenEstimate,
                              Integer manifestTokenTotal) throws Exception {
        try (Connection conn = connect();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO usage_events (timestamp, event_type, project, query, unit_id, " +
                     "token_estimate, manifest_token_total) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, eventType);
            ps.setString(3, project);
            ps.setString(4, query);
            ps.setString(5, unitId);
            if (tokenEstimate != null)       ps.setInt(6, tokenEstimate);
            else                             ps.setNull(6, Types.INTEGER);
            if (manifestTokenTotal != null)  ps.setInt(7, manifestTokenTotal);
            else                             ps.setNull(7, Types.INTEGER);
            ps.executeUpdate();
        }
    }

    private Connection connect() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + tempDb);
    }
}
