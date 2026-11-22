package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.Decision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecisionStoreTest {

    @TempDir Path tmp;

    private Path tempDb;
    private MemoryDatabase db;
    private DecisionStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-decision-store-test-", ".db");
        db     = new MemoryDatabase(tempDb);
        store  = new DecisionStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    private static Decision decision(String id, String what, String project) {
        return new Decision(id, "anti-pattern", "deployment", what,
                "Inline code has a 4KB limit.", List.of(), "session-abc", null, List.of("lambda"), project);
    }

    @Test
    void identicalRecordsInSeveralCheckoutsCollapseToOneHit() throws Exception {
        String what = "Do not use inline code for Lambda handlers";
        store.upsert(decision("lambda-inline", what, "/src/kompo.ai/.claude/worktrees/agent-1"));
        store.upsert(decision("lambda-inline", what, "/src/agent-branches/kompo.ai-2331"));
        store.upsert(decision("lambda-inline", what, "/src/kompo.ai"));
        store.upsert(decision("lambda-inline", what, "/src/kompo.ai-2"));

        List<DecisionStore.DecisionHit> hits = store.searchCollapsed("lambda inline", null, null, 10);

        assertEquals(1, hits.size());
        assertEquals("/src/kompo.ai", hits.get(0).decision().projectPath(),
                "shortest non-worktree path is the representative");
        assertEquals(3, hits.get(0).otherProjects().size());
    }

    @Test
    void editedVersionOfSameIdStaysSeparate() throws Exception {
        store.upsert(decision("lambda-inline", "Do not use inline code for Lambda handlers", "/src/kompo.ai"));
        store.upsert(decision("lambda-inline", "Do not use inline code for Lambda handlers", "/src/kompo.ai-2"));
        store.upsert(decision("lambda-inline", "Inline Lambda code is fine below 2KB", "/src/kompo.ai-branch"));

        List<DecisionStore.DecisionHit> hits = store.searchCollapsed("lambda inline", null, null, 10);

        assertEquals(2, hits.size());
        assertEquals(1, hits.stream().filter(h -> h.otherProjects().size() == 1).count());
    }

    @Test
    void limitCountsDistinctRecordsNotDuplicates() throws Exception {
        for (int copy = 0; copy < 30; copy++) {
            for (int rec = 0; rec < 4; rec++) {
                store.upsert(decision("lambda-" + rec, "Lambda rule " + rec, "/src/checkout-" + copy));
            }
        }

        assertEquals(3, store.searchCollapsed("lambda", null, null, 3).size());
        assertEquals(4, store.searchCollapsed("lambda", null, null, 10).size());
        assertEquals(4, store.filterCollapsed("anti-pattern", null, 10).size());
    }

    @Test
    void typeAndDomainFiltersApplyBeforeCollapsing() throws Exception {
        store.upsert(decision("lambda-inline", "Lambda rule", "/src/a"));
        store.upsert(decision("lambda-inline", "Lambda rule", "/src/b"));

        assertEquals(1, store.searchCollapsed("lambda", "anti-pattern", "deployment", 10).size());
        assertTrue(store.searchCollapsed("lambda", "decision", null, 10).isEmpty());
        assertTrue(store.searchCollapsed("lambda", null, "testing", 10).isEmpty());
    }

    @Test
    void linkedGitWorktreeRanksBelowMainCheckout() throws Exception {
        Path main = Files.createDirectories(tmp.resolve("repo"));
        Files.createDirectories(main.resolve(".git"));
        Path linked = Files.createDirectories(tmp.resolve("r"));   // shorter path, but a linked worktree
        Files.writeString(linked.resolve(".git"), "gitdir: " + main.resolve(".git/worktrees/r") + "\n");

        assertEquals(0, DecisionStore.checkoutRank(main.toString()));
        assertEquals(1, DecisionStore.checkoutRank(linked.toString()));
        assertEquals(2, DecisionStore.checkoutRank("/src/kompo.ai/.claude/worktrees/x"));

        store.upsert(decision("lambda-inline", "Lambda rule", linked.toString()));
        store.upsert(decision("lambda-inline", "Lambda rule", main.toString()));
        assertEquals(main.toString(),
                store.searchCollapsed("lambda", null, null, 10).get(0).decision().projectPath());
    }

    // ---- source (#97)

    private static Decision from(String id, String what, String project, Decision.Source source) {
        return new Decision(id, "anti-pattern", "deployment", what, "Inline code has a 4KB limit.",
                List.of(), "session-abc", null, List.of("lambda"), project, source);
    }

    @Test
    void aDecisionWithoutASourceIsUnverified() {
        assertEquals(Decision.Source.SESSION, decision("a", "Lambda rule", "/src/a").source());
        assertEquals(Decision.Source.SESSION, Decision.Source.fromValue(null));
        assertEquals(Decision.Source.SESSION, Decision.Source.fromValue("anything else"));
        assertEquals(Decision.Source.CONFIGURED, Decision.Source.fromValue("configured"));
    }

    @Test
    void sourceIsStoredAndUpdatedOnConflict() throws Exception {
        store.upsert(from("lambda-inline", "Lambda rule", "/src/a", Decision.Source.SESSION));
        assertEquals(Decision.Source.SESSION, store.getById("lambda-inline").source());

        store.upsert(from("lambda-inline", "Lambda rule", "/src/a", Decision.Source.CONFIGURED));

        assertEquals(1, store.count());
        assertEquals(Decision.Source.CONFIGURED, store.getById("lambda-inline").source());
    }

    @Test
    void aRowWrittenBeforeTheMigrationReadsAsUnverified() throws Exception {
        try (var st = db.getConnection().createStatement()) {
            st.executeUpdate("INSERT INTO decisions (decision_id, type, domain, what, why, learned, project_path, file_path, scanned_at) "
                    + "VALUES ('old','decision','x','Old record','Written without a source','l','/src/old','/src/old/.sdd/decisions/index.yaml', datetime('now'))");
        }
        assertEquals(Decision.Source.SESSION, store.filter(null, null, 10).get(0).source());
    }

    @Test
    void aHitIsConfiguredWhenAnyIdenticalCopyIsConfigured() throws Exception {
        store.upsert(from("lambda-inline", "Lambda rule", "/src/clone-1", Decision.Source.SESSION));
        store.upsert(from("lambda-inline", "Lambda rule", "/src/clone-2", Decision.Source.CONFIGURED));

        List<DecisionStore.DecisionHit> hits = store.searchCollapsed("lambda", null, null, 10);

        assertEquals(1, hits.size());
        assertEquals(Decision.Source.CONFIGURED, hits.get(0).source());
    }

    @Test
    void aHitStaysUnverifiedWhenEveryCopyIsSessionDiscovered() throws Exception {
        store.upsert(from("lambda-inline", "Lambda rule", "/src/clone-1", Decision.Source.SESSION));
        store.upsert(from("lambda-inline", "Lambda rule", "/src/clone-2", Decision.Source.SESSION));

        assertEquals(Decision.Source.SESSION, store.searchCollapsed("lambda", null, null, 10).get(0).source());
    }
}
