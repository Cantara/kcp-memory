package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.ToolEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventStoreTest {

    private Path tempDb;
    private MemoryDatabase db;
    private EventStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-event-test-", ".db");
        db     = new MemoryDatabase(tempDb);
        store  = new EventStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    @Test
    void searchFindsExactCommand() throws SQLException {
        store.insert(makeEvent("kubectl apply -f deploy.yaml"));
        List<ToolEvent> results = store.search("kubectl", 10);
        assertFalse(results.isEmpty());
        assertTrue(results.get(0).command().contains("kubectl"));
    }

    @Test
    void searchWithHyphenatedQueryDoesNotThrow() throws SQLException {
        store.insert(makeEvent("docker build -t my-service:latest ."));
        // Before fix: FTS5 would throw [SQLITE_ERROR] no such column: service
        assertDoesNotThrow(() -> store.search("my-service", 10));
    }

    @Test
    void searchWithHyphenatedQueryFindsMatches() throws SQLException {
        store.insert(makeEvent("docker build -t my-service:latest ."));
        List<ToolEvent> results = store.search("my-service", 10);
        assertFalse(results.isEmpty(), "Expected to find event with hyphenated term");
    }

    @Test
    void searchWithMultipleHyphenatedTokens() throws SQLException {
        store.insert(makeEvent("kubectl apply -f kcp-memory-daemon.yaml"));
        List<ToolEvent> results = store.search("kcp-memory-daemon", 10);
        assertFalse(results.isEmpty());
    }

    @Test
    void searchReturnsEmptyForNoMatch() throws SQLException {
        store.insert(makeEvent("git status"));
        List<ToolEvent> results = store.search("nonexistent-term", 10);
        assertTrue(results.isEmpty());
    }

    private ToolEvent makeEvent(String command) {
        return new ToolEvent(
                0,
                "2026-05-19T10:00:00Z",
                "session-abc123",
                "/src/test-project",
                "Bash",
                command,
                null,
                null,
                null,
                "2026-05-19T10:00:00Z"
        );
    }
}
