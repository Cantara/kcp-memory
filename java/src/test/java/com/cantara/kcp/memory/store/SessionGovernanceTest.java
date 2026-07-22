package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.model.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Governance behaviour of the memory store: provenance, retention, and the
 * right-to-forget, plus the recall gate that skips expired/forgotten memories.
 */
class SessionGovernanceTest {

    private Path tempDb;
    private MemoryDatabase db;
    private SessionStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-gov-test-", ".db");
        db = new MemoryDatabase(tempDb);
        store = new SessionStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    // --- provenance -----------------------------------------------------

    @Test
    void provenanceIsAutoDerivedOnUpsert() throws Exception {
        store.upsert(makeSession("sess-prov", "/src/proj", "Wire up billing"));
        SessionStore.Governance g = store.getGovernance("sess-prov");
        assertNotNull(g);
        assertNotNull(g.provenance());
        assertTrue(g.provenance().contains("sess-prov"), "provenance should reference the source session");
    }

    @Test
    void recalledItemsCarryProvenance() throws Exception {
        Session s = makeSession("sess-carry", "/src/proj", "OAuth login flow");
        s.setAllUserText("Implement OAuth login flow with PKCE");
        store.upsert(s);

        List<SearchResult> results = store.search("OAuth", 5);
        assertEquals(1, results.size());
        assertNotNull(results.get(0).getProvenance());
    }

    @Test
    void explicitProvenanceIsPreservedAcrossRescans() throws Exception {
        Session s = makeSession("sess-explicit", "/src/proj", "seed");
        s.setProvenance("import:legacy-archive#42");
        store.upsert(s);
        // Re-scan with no provenance set must not clobber the recorded one.
        store.upsert(makeSession("sess-explicit", "/src/proj", "seed"));
        assertEquals("import:legacy-archive#42", store.getGovernance("sess-explicit").provenance());
    }

    // --- retention gate -------------------------------------------------

    @Test
    void searchSkipsExpiredMemory() throws Exception {
        Session s = makeSession("sess-exp", "/src/proj", "Kubernetes deploy notes");
        s.setAllUserText("Kubernetes deploy rollout strategy");
        store.upsert(s);

        assertFalse(store.search("Kubernetes", 5).isEmpty());

        store.setRetention("sess-exp", "2000-01-01T00:00:00Z"); // already past
        assertTrue(store.search("Kubernetes", 5).isEmpty(), "expired memory must not be recalled");
    }

    @Test
    void futureRetentionStillRecalls() throws Exception {
        Session s = makeSession("sess-future", "/src/proj", "Flyway migration");
        s.setAllUserText("Flyway migration baseline");
        store.upsert(s);
        store.setRetention("sess-future", "2099-01-01T00:00:00Z");
        assertEquals(1, store.search("Flyway", 5).size());
    }

    @Test
    void clearingRetentionReExposesMemory() throws Exception {
        Session s = makeSession("sess-clear", "/src/proj", "Docker build cache");
        s.setAllUserText("Docker build cache optimization");
        store.upsert(s);
        store.setRetention("sess-clear", "2000-01-01T00:00:00Z");
        assertTrue(store.search("Docker", 5).isEmpty());
        store.setRetention("sess-clear", null); // clear expiry
        assertEquals(1, store.search("Docker", 5).size());
    }

    // --- right-to-forget ------------------------------------------------

    @Test
    void forgetTombstonesFromRecallButRetainsRow() throws Exception {
        Session s = makeSession("sess-forget", "/src/proj", "Postgres tuning");
        s.setAllUserText("Postgres tuning for write throughput");
        store.upsert(s);

        assertFalse(store.search("Postgres", 5).isEmpty());

        assertTrue(store.forget("sess-forget", "user asked to forget"));
        assertTrue(store.search("Postgres", 5).isEmpty(), "forgotten memory must not be recalled");
        assertTrue(store.list("/src/proj", 10).isEmpty(), "forgotten memory must not appear in list");

        // Row retained for audit
        assertNotNull(store.getById("sess-forget"));
        SessionStore.Governance g = store.getGovernance("sess-forget");
        assertNotNull(g.forgottenAt());
        assertEquals("user asked to forget", g.forgetReason());
    }

    @Test
    void forgetUnknownSessionReturnsFalse() throws Exception {
        assertFalse(store.forget("does-not-exist", "noop"));
    }

    @Test
    void listGateSkipsExpiredAndForgotten() throws Exception {
        store.upsert(makeSession("live-1", "/src/g", "Live one"));
        store.upsert(makeSession("exp-1", "/src/g", "Expired one"));
        store.upsert(makeSession("forgot-1", "/src/g", "Forgotten one"));

        store.setRetention("exp-1", "2000-01-01T00:00:00Z");
        store.forget("forgot-1", "cleanup");

        List<SearchResult> live = store.list("/src/g", 10);
        assertEquals(1, live.size());
        assertEquals("live-1", live.get(0).getSessionId());
    }

    // --- audit ----------------------------------------------------------

    @Test
    void auditSearchReportsSkippedWithReasons() throws Exception {
        Session live = makeSession("audit-live", "/src/a", "audit topic alpha");
        live.setAllUserText("audit topic alpha content");
        Session exp = makeSession("audit-exp", "/src/a", "audit topic bravo");
        exp.setAllUserText("audit topic bravo content");
        Session forgot = makeSession("audit-forgot", "/src/a", "audit topic charlie");
        forgot.setAllUserText("audit topic charlie content");
        store.upsert(live);
        store.upsert(exp);
        store.upsert(forgot);

        store.setRetention("audit-exp", "2000-01-01T00:00:00Z");
        store.forget("audit-forgot", "sensitive");

        List<SessionStore.RecallAudit> audit = store.auditSearch("audit", 10);
        assertEquals(3, audit.size(), "audit must include gated-out candidates");

        var byId = audit.stream().collect(
                java.util.stream.Collectors.toMap(SessionStore.RecallAudit::sessionId, a -> a));
        assertTrue(byId.get("audit-live").allowed());
        assertNull(byId.get("audit-live").reason());

        assertFalse(byId.get("audit-exp").allowed());
        assertTrue(byId.get("audit-exp").reason().contains("expired"));

        assertFalse(byId.get("audit-forgot").allowed());
        assertTrue(byId.get("audit-forgot").reason().contains("forgotten"));
        assertTrue(byId.get("audit-forgot").reason().contains("sensitive"));
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
