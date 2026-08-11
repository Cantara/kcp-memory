package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.CommandPattern;
import com.cantara.kcp.memory.model.ToolEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatternStoreTest {

    private Path tempDb;
    private MemoryDatabase db;
    private EventStore eventStore;
    private PatternStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb     = Files.createTempFile("kcp-pattern-test-", ".db");
        db         = new MemoryDatabase(tempDb);
        eventStore = new EventStore(db);
        store      = new PatternStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    @Test
    void findsCommandRecurringAcrossEnoughSessions() throws SQLException {
        insert("npm run lint -- --fix", "session-1");
        insert("npm run lint -- --fix", "session-2");
        insert("npm run lint -- --fix", "session-3");

        List<CommandPattern> patterns = store.findRecurringCommands(30, 3, 20);

        assertEquals(1, patterns.size());
        CommandPattern p = patterns.get(0);
        assertEquals("npm run lint -- --fix", p.command());
        assertEquals(3, p.occurrenceCount());
        assertEquals(3, p.sessionCount());
        assertEquals(3, p.sessionIds().size());
    }

    @Test
    void excludesCommandBelowMinSessionThreshold() throws SQLException {
        // Same command, but only 2 distinct sessions — below the default min of 3
        insert("git status --short", "session-1");
        insert("git status --short", "session-1");
        insert("git status --short", "session-2");

        List<CommandPattern> patterns = store.findRecurringCommands(30, 3, 20);

        assertTrue(patterns.isEmpty(), "single-session repeats and 2-session commands should not qualify at min-sessions=3");
    }

    @Test
    void repeatedCallsWithinOneSessionDoNotCountAsMultipleSessions() throws SQLException {
        for (int i = 0; i < 10; i++) {
            insert("dotnet test --filter Category=Fast", "session-1");
        }

        List<CommandPattern> patterns = store.findRecurringCommands(30, 3, 20);

        assertTrue(patterns.isEmpty(), "10 repeats in ONE session must not satisfy a 3-session threshold");
    }

    @Test
    void distinctCommandsAreNotGroupedTogether() throws SQLException {
        insert("dotnet test --filter Category=Fast", "session-1");
        insert("dotnet test --filter Category=Fast", "session-2");
        insert("dotnet test --filter Category=Fast", "session-3");
        insert("dotnet build", "session-1");
        insert("dotnet build", "session-2");
        insert("dotnet build", "session-3");

        List<CommandPattern> patterns = store.findRecurringCommands(30, 3, 20);

        assertEquals(2, patterns.size());
        List<String> commands = patterns.stream().map(CommandPattern::command).toList();
        assertTrue(commands.contains("dotnet test --filter Category=Fast"));
        assertTrue(commands.contains("dotnet build"));
    }

    @Test
    void respectsLimit() throws SQLException {
        for (int cmd = 0; cmd < 5; cmd++) {
            for (int sess = 0; sess < 3; sess++) {
                insert("cmd-" + cmd, "session-" + sess);
            }
        }

        List<CommandPattern> patterns = store.findRecurringCommands(30, 3, 2);

        assertEquals(2, patterns.size());
    }

    @Test
    void eventsOutsideSinceDaysWindowAreExcluded() throws SQLException {
        String old = Instant.now().minusSeconds(60L * 60 * 24 * 90).toString(); // 90 days ago
        insert("old-command", "session-1", old);
        insert("old-command", "session-2", old);
        insert("old-command", "session-3", old);

        List<CommandPattern> patterns = store.findRecurringCommands(30, 3, 20);

        assertTrue(patterns.isEmpty(), "events older than --since window must not count toward the pattern");
    }

    private void insert(String command, String sessionId) throws SQLException {
        insert(command, sessionId, Instant.now().toString());
    }

    private void insert(String command, String sessionId, String eventTs) throws SQLException {
        eventStore.insert(new ToolEvent(
                0,
                eventTs,
                sessionId,
                "/src/test-project",
                "Bash",
                command,
                null,
                null,
                null,
                eventTs
        ));
    }
}
