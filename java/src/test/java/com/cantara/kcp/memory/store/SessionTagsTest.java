package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.model.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for V10 session tags: addTags, removeTags, list --tag filter, upsert preserves tags.
 */
class SessionTagsTest {

    private Path tempDb;
    private MemoryDatabase db;
    private SessionStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-tags-test-", ".db");
        db = new MemoryDatabase(tempDb);
        store = new SessionStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    private Session makeSession(String id, String project, String firstMsg) {
        Session s = new Session();
        s.setSessionId(id);
        s.setProjectDir(project);
        s.setGitBranch("HEAD");
        s.setSlug("test-slug");
        s.setModel("claude-sonnet-4-6");
        s.setStartedAt("2026-04-18T10:00:00");
        s.setEndedAt("2026-04-18T10:30:00");
        s.setTurnCount(5);
        s.setToolCallCount(3);
        s.setFirstMessage(firstMsg);
        s.setAllUserText(firstMsg);
        s.setScannedAt("2026-04-18T10:31:00");
        return s;
    }

    @Test
    void addTagsByExactId() throws Exception {
        String id = "aaaa1111-0000-0000-0000-000000000000";
        store.upsert(makeSession(id, "/home/user/Documents", "debug kcp routing issue"));

        boolean updated = store.addTags(id, List.of("ExoCortex-CC", "kcp-routing"));
        assertTrue(updated);

        List<SearchResult> results = store.list(null, null, "kcp-routing", 10);
        assertEquals(1, results.size());
        assertTrue(results.get(0).getSessionTags().contains("kcp-routing"));
        assertTrue(results.get(0).getSessionTags().contains("ExoCortex-CC"));
    }

    @Test
    void addTagsByPrefix() throws Exception {
        String id = "bbbb2222-0000-0000-0000-000000000000";
        store.upsert(makeSession(id, "/home/user/Documents", "mynder PR review 919"));

        boolean updated = store.addTags("bbbb2222", List.of("mynder", "pr-919"));
        assertTrue(updated);

        List<SearchResult> results = store.list(null, null, "mynder", 10);
        assertEquals(1, results.size());
        assertTrue(results.get(0).getSessionTags().contains("mynder"));
        assertTrue(results.get(0).getSessionTags().contains("pr-919"));
    }

    @Test
    void tagNotFound() throws Exception {
        boolean updated = store.addTags("nonexistent", List.of("some-tag"));
        assertFalse(updated);
    }

    @Test
    void ambiguousPrefixThrows() throws Exception {
        store.upsert(makeSession("cccc1111-0000-0000-0000-000000000000", "/p", "session A"));
        store.upsert(makeSession("cccc2222-0000-0000-0000-000000000000", "/p", "session B"));

        var ex = assertThrows(java.sql.SQLException.class,
                () -> store.addTags("cccc", List.of("some-tag")));
        assertTrue(ex.getMessage().contains("Ambiguous prefix"));
    }

    @Test
    void upsertPreservesTags() throws Exception {
        String id = "dddd3333-0000-0000-0000-000000000000";
        store.upsert(makeSession(id, "/home/user/Documents", "initial message"));
        store.addTags(id, List.of("important", "mynder"));

        // Re-scan: upsert with updated content
        Session updated = makeSession(id, "/home/user/Documents", "initial message");
        updated.setTurnCount(10);
        store.upsert(updated);

        List<SearchResult> results = store.list(null, null, "mynder", 10);
        assertEquals(1, results.size(), "tags should survive re-scan");
        assertTrue(results.get(0).getSessionTags().contains("important"));
        assertTrue(results.get(0).getSessionTags().contains("mynder"));
    }

    @Test
    void removeTags() throws Exception {
        String id = "eeee4444-0000-0000-0000-000000000000";
        store.upsert(makeSession(id, "/home/user/Documents", "some message"));
        store.addTags(id, List.of("ExoCortex-CC", "mynder", "pr-919"));

        boolean updated = store.removeTags(id, List.of("mynder"));
        assertTrue(updated);

        List<SearchResult> results = store.list(null, null, "ExoCortex", 10);
        assertEquals(1, results.size());
        assertFalse(results.get(0).getSessionTags().contains("mynder"));
        assertTrue(results.get(0).getSessionTags().contains("ExoCortex-CC"));
        assertTrue(results.get(0).getSessionTags().contains("pr-919"));
    }

    @Test
    void addTagsMergesNoDuplicates() throws Exception {
        String id = "ffff5555-0000-0000-0000-000000000000";
        store.upsert(makeSession(id, "/home/user/Documents", "message"));
        store.addTags(id, List.of("ExoCortex-CC", "mynder"));
        store.addTags(id, List.of("mynder", "pr-919")); // mynder duplicate

        List<SearchResult> results = store.list(null, null, "ExoCortex", 10);
        assertEquals(1, results.size());
        List<String> tags = results.get(0).getSessionTags();
        assertEquals(3, tags.size(), "should have exactly 3 unique tags");
        assertTrue(tags.contains("ExoCortex-CC"));
        assertTrue(tags.contains("mynder"));
        assertTrue(tags.contains("pr-919"));
    }

    @Test
    void listWithoutTagFilterReturnsAll() throws Exception {
        store.upsert(makeSession("a1111111-0000-0000-0000-000000000000", "/p", "session 1"));
        store.upsert(makeSession("a2222222-0000-0000-0000-000000000000", "/p", "session 2"));
        store.addTags("a1111111", List.of("tagged-session"));

        List<SearchResult> all = store.list(null, null, null, 10);
        assertEquals(2, all.size());
    }
}
