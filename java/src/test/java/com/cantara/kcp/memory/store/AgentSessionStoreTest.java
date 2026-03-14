package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.AgentSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentSessionStoreTest {

    private Path tempDb;
    private MemoryDatabase db;
    private AgentSessionStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-test-", ".db");
        db     = new MemoryDatabase(tempDb);
        store  = new AgentSessionStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        Files.deleteIfExists(tempDb);
    }

    private AgentSession buildAgent(String agentId, String parentSessionId, String slug, String cwd,
                                     String firstMessage) {
        AgentSession a = new AgentSession();
        a.setAgentId(agentId);
        a.setParentSessionId(parentSessionId);
        a.setAgentSlug(slug);
        a.setCwd(cwd);
        a.setProjectDir(cwd);
        a.setFirstMessage(firstMessage);
        a.setAllUserText(firstMessage);
        a.setTurnCount(3);
        a.setToolCallCount(5);
        a.setToolNames(List.of("Read", "Glob", "Bash"));
        a.setMessageCount(12);
        a.setFirstSeenAt("2026-03-14T13:48:00Z");
        a.setLastUpdatedAt("2026-03-14T14:19:00Z");
        a.setScannedAt("2026-03-14T14:20:00Z");
        return a;
    }

    @Test
    void upsertAndRetrieveByParent() throws Exception {
        AgentSession a = buildAgent(
                "ae5fb06d98fb195b2",
                "624a7854-a7d2-4331-8ddb-7dab21e7064c",
                "tingly-soaring-naur",
                "/src/exoreaction",
                "You are filling skills gaps for the eXOReaction ecosystem."
        );
        store.upsert(a);

        List<AgentSession> results = store.listByParent("624a7854-a7d2-4331-8ddb-7dab21e7064c", 10);
        assertEquals(1, results.size());

        AgentSession retrieved = results.get(0);
        assertEquals("ae5fb06d98fb195b2", retrieved.getAgentId());
        assertEquals("624a7854-a7d2-4331-8ddb-7dab21e7064c", retrieved.getParentSessionId());
        assertEquals("tingly-soaring-naur", retrieved.getAgentSlug());
        assertEquals("/src/exoreaction", retrieved.getCwd());
        assertEquals(3, retrieved.getTurnCount());
        assertEquals(5, retrieved.getToolCallCount());
    }

    @Test
    void upsertIsIdempotent() throws Exception {
        AgentSession a = buildAgent("agent-001", "parent-001", "slug-a", "/src/foo", "first message");
        store.upsert(a);
        store.upsert(a); // second upsert — should not throw or duplicate

        List<AgentSession> results = store.listByParent("parent-001", 10);
        assertEquals(1, results.size(), "Upsert should be idempotent — no duplicate rows");
    }

    @Test
    void searchFindsByContent() throws Exception {
        store.upsert(buildAgent("agent-001", "parent-001", "slug-a", "/src/foo",
                "CatalystOne HRIS event-sourcing platform"));
        store.upsert(buildAgent("agent-002", "parent-001", "slug-b", "/src/bar",
                "Elprint PCB design components"));

        List<AgentSession> results = store.search("CatalystOne", 10);
        assertEquals(1, results.size());
        assertEquals("agent-001", results.get(0).getAgentId());
    }

    @Test
    void searchWithParentFilter() throws Exception {
        store.upsert(buildAgent("agent-001", "parent-AAA", "slug-a", "/src/foo",
                "CatalystOne HRIS event-sourcing platform"));
        store.upsert(buildAgent("agent-002", "parent-BBB", "slug-b", "/src/bar",
                "CatalystOne notification service"));

        // Searching without parent filter returns both
        List<AgentSession> all = store.search("CatalystOne", 10);
        assertEquals(2, all.size());

        // Filtering by parent returns only one
        List<AgentSession> filtered = store.search("CatalystOne", "parent-AAA", 10);
        assertEquals(1, filtered.size());
        assertEquals("agent-001", filtered.get(0).getAgentId());
    }

    @Test
    void getScannedAtReturnsNullForUnknownAgent() throws Exception {
        assertNull(store.getScannedAt("nonexistent-agent-id"));
    }

    @Test
    void getScannedAtReturnsTimestampAfterUpsert() throws Exception {
        AgentSession a = buildAgent("agent-001", "parent-001", "slug-a", "/src/foo", "hello");
        store.upsert(a);

        String scannedAt = store.getScannedAt("agent-001");
        assertNotNull(scannedAt);
        assertEquals("2026-03-14T14:20:00Z", scannedAt);
    }

    @Test
    void statsReflectsMultipleAgents() throws Exception {
        store.upsert(buildAgent("agent-001", "parent-AAA", "slug-a", "/src/a", "task one"));
        store.upsert(buildAgent("agent-002", "parent-AAA", "slug-b", "/src/b", "task two"));
        store.upsert(buildAgent("agent-003", "parent-BBB", "slug-c", "/src/c", "task three"));

        AgentSessionStore.Stats stats = store.stats();
        assertEquals(3, stats.totalAgents());
        assertEquals(2, stats.parentSessions());
        assertEquals(9, stats.totalTurns());   // 3 turns × 3 agents
        assertEquals(15, stats.totalToolCalls()); // 5 calls × 3 agents
    }
}
