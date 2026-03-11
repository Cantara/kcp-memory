package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.model.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {

    private Path tempDb;
    private MemoryDatabase db;
    private SessionStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-test-", ".db");
        db = new MemoryDatabase(tempDb);
        store = new SessionStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    @Test
    void upsertAndGetScannedAt() throws SQLException {
        Session s = makeSession("sess-001", "/src/my-project", "Add authentication");
        store.upsert(s);

        String scannedAt = store.getScannedAt("sess-001");
        assertNotNull(scannedAt);
        assertTrue(scannedAt.startsWith("20")); // ISO-8601
    }

    @Test
    void upsertIsIdempotent() throws SQLException {
        Session s = makeSession("sess-002", "/src/project", "Initial query");
        store.upsert(s);
        s.setTurnCount(10);
        store.upsert(s); // should update, not throw

        var stats = store.stats();
        assertEquals(1, stats.totalSessions());
        assertEquals(10, stats.totalTurns());
    }

    @Test
    void listReturnsSessionsNewestFirst() throws SQLException {
        Session s1 = makeSession("sess-003", "/src/a", "First session");
        s1.setStartedAt("2026-01-01T10:00:00Z");

        Session s2 = makeSession("sess-004", "/src/a", "Second session");
        s2.setStartedAt("2026-02-01T10:00:00Z");

        store.upsert(s1);
        store.upsert(s2);

        List<SearchResult> results = store.list(null, 10);
        assertEquals(2, results.size());
        assertEquals("sess-004", results.get(0).getSessionId()); // newest first
    }

    @Test
    void listFiltersByProject() throws SQLException {
        store.upsert(makeSession("sess-005", "/src/alpha", "Alpha work"));
        store.upsert(makeSession("sess-006", "/src/beta",  "Beta work"));

        List<SearchResult> alphaOnly = store.list("/src/alpha", 10);
        assertEquals(1, alphaOnly.size());
        assertEquals("/src/alpha", alphaOnly.get(0).getProjectDir());
    }

    @Test
    void searchFindsByKeyword() throws SQLException {
        Session s = makeSession("sess-007", "/src/proj", "Implement OAuth login");
        s.setAllUserText("I need to implement OAuth login with Google");
        store.upsert(s);

        List<SearchResult> results = store.search("OAuth", 5);
        assertFalse(results.isEmpty());
        assertEquals("sess-007", results.get(0).getSessionId());
    }

    @Test
    void searchTreatsHyphenatedTermsAsLiteralText() throws SQLException {
        Session s = makeSession("sess-010", "/src/proj", "Configure queue-secret header");
        s.setAllUserText("The X-API-Key is queue-secret for local testing");
        store.upsert(s);

        List<SearchResult> results = store.search("queue-secret API key", 5);
        assertFalse(results.isEmpty());
        assertEquals("sess-010", results.get(0).getSessionId());
    }

    @Test
    void statsAggregatesCorrectly() throws SQLException {
        Session s1 = makeSession("sess-008", "/src/x", "Task one");
        s1.setTurnCount(5);
        s1.setToolCallCount(12);

        Session s2 = makeSession("sess-009", "/src/x", "Task two");
        s2.setTurnCount(3);
        s2.setToolCallCount(7);

        store.upsert(s1);
        store.upsert(s2);

        SessionStore.Stats stats = store.stats();
        assertEquals(2, stats.totalSessions());
        assertEquals(8,  stats.totalTurns());
        assertEquals(19, stats.totalToolCalls());
    }

    private Session makeSession(String id, String projectDir, String firstMessage) {
        Session s = new Session();
        s.setSessionId(id);
        s.setProjectDir(projectDir);
        s.setFirstMessage(firstMessage);
        s.setAllUserText(firstMessage);
        s.setStartedAt(Instant.now().toString());
        s.setScannedAt(Instant.now().toString());
        s.setTurnCount(2);
        s.setToolCallCount(3);
        s.setToolNames(List.of("Read", "Bash"));
        s.setFiles(List.of());
        return s;
    }
}
