package com.cantara.kcp.memory.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NavTrajectoryStoreTest {

    private Path tempDb;
    private MemoryDatabase db;
    private NavTrajectoryStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-nav-test-", ".db");
        db     = new MemoryDatabase(tempDb);
        store  = new NavTrajectoryStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    @Test
    void recordAndRecentRoundTrip() throws Exception {
        store.record("ls", "/sessions", "corr-1", "returned 3 entries");

        List<NavTrajectoryStore.Entry> results = store.recent(10, null, null);
        assertEquals(1, results.size());
        assertEquals("ls", results.get(0).tool());
        assertEquals("/sessions", results.get(0).path());
        assertEquals("corr-1", results.get(0).sessionId());
        assertEquals("returned 3 entries", results.get(0).summary());
    }

    @Test
    void recentFiltersBySessionId() throws Exception {
        store.record("ls",   "/sessions",         "corr-A", "returned 1 entry");
        store.record("read", "/decisions/dec-1",  "corr-B", "returned 200B from /decisions");

        List<NavTrajectoryStore.Entry> filtered = store.recent(10, "corr-A", null);
        assertEquals(1, filtered.size());
        assertEquals("corr-A", filtered.get(0).sessionId());
        assertEquals("/sessions", filtered.get(0).path());
    }

    @Test
    void recentOrdersNewestFirst() throws Exception {
        // Insert with explicit, distinct ts values directly (bypassing record()'s
        // Instant.now()) so ordering is deterministic and not a wall-clock race.
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "INSERT INTO nav_trajectory (ts, tool, path, session_id, summary) VALUES (?,?,?,?,?)")) {
            ps.setString(1, "2026-01-01T10:00:00Z");
            ps.setString(2, "ls");
            ps.setString(3, "/sessions");
            ps.setNull(4, Types.VARCHAR);
            ps.setString(5, "first call");
            ps.executeUpdate();

            ps.setString(1, "2026-01-01T10:05:00Z");
            ps.setString(2, "ls");
            ps.setString(3, "/decisions");
            ps.setNull(4, Types.VARCHAR);
            ps.setString(5, "second call");
            ps.executeUpdate();
        }

        List<NavTrajectoryStore.Entry> results = store.recent(10, null, null);
        assertEquals(2, results.size());
        assertEquals("second call", results.get(0).summary(), "newest ts must sort first");
        assertEquals("first call",  results.get(1).summary());
    }

    @Test
    void recentRespectsLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            store.record("ls", "/sessions", null, "call " + i);
        }
        List<NavTrajectoryStore.Entry> results = store.recent(2, null, null);
        assertEquals(2, results.size());
    }
}
