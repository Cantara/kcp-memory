package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.ManifestEditProposal;
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

class ManifestEditProposalStoreTest {

    private Path tempDb;
    private MemoryDatabase db;
    private EventStore eventStore;
    private ManifestEditProposalStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb     = Files.createTempFile("kcp-proposal-test-", ".db");
        db         = new MemoryDatabase(tempDb);
        eventStore = new EventStore(db);
        store      = new ManifestEditProposalStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    @Test
    void proposesManifestWithHighRetryRateAndAttachesEvidence() throws SQLException {
        // 3 sessions, each: call the manifest, then retry it within 90s — 100% retry rate
        for (int i = 1; i <= 3; i++) {
            String session = "session-" + i;
            Instant t0 = Instant.now();
            insert("dotnet-test", session, t0, null);
            insert("dotnet-test", session, t0.plusSeconds(10), null);
        }

        List<ManifestEditProposal> proposals = store.proposeEdits(30, 1, 0.15, 5);

        assertEquals(1, proposals.size());
        ManifestEditProposal p = proposals.get(0);
        assertEquals("dotnet-test", p.manifestKey());
        assertTrue(p.reason().contains("retry"), "expected retry to be the dominant reason: " + p.reason());
        assertEquals(3, p.totalSessions());
        assertEquals(3, p.affectedSessions());
        assertEquals(3, p.retrySessions());
        assertEquals(3, p.evidenceSessionIds().size());
    }

    @Test
    void excludesManifestBelowScoreThreshold() throws SQLException {
        // Single clean call per session, no retries/errors/help — quality score 0
        for (int i = 1; i <= 5; i++) {
            insert("clean-manifest", "session-" + i, Instant.now(), null);
        }

        List<ManifestEditProposal> proposals = store.proposeEdits(30, 1, 0.15, 5);

        assertTrue(proposals.isEmpty());
    }

    @Test
    void excludesManifestBelowMinCalls() throws SQLException {
        Instant t0 = Instant.now();
        insert("rare-manifest", "session-1", t0, null);
        insert("rare-manifest", "session-1", t0.plusSeconds(5), null);

        // min-calls=5 excludes it even though its retry rate would otherwise qualify
        List<ManifestEditProposal> proposals = store.proposeEdits(30, 5, 0.15, 5);

        assertTrue(proposals.isEmpty());
    }

    @Test
    void errorSignalDominatesReasonWhenHighestSignal() throws SQLException {
        for (int i = 1; i <= 3; i++) {
            insert("flaky-manifest", "session-" + i, Instant.now(), "Error: something exploded");
        }

        List<ManifestEditProposal> proposals = store.proposeEdits(30, 1, 0.15, 5);

        assertEquals(1, proposals.size());
        assertTrue(proposals.get(0).reason().contains("error"), "expected error to dominate: " + proposals.get(0).reason());
        assertEquals(3, proposals.get(0).errorSessions());
    }

    @Test
    void excludesNonSkillKeysEvenWhenQualityScoreWouldQualify() throws SQLException {
        // SUPPRESSED and FILTER:* are kcp-commands markers, not authored skills —
        // never proposal candidates regardless of how bad their signals look.
        for (int i = 1; i <= 3; i++) {
            insert("SUPPRESSED", "session-" + i, Instant.now(), "Error: nope");
        }
        for (int i = 1; i <= 3; i++) {
            insert("FILTER:grep", "session-" + i, Instant.now(), "Error: nope");
        }
        // A real, otherwise-identical manifest key should still be proposed.
        for (int i = 1; i <= 3; i++) {
            insert("real-skill", "session-" + i, Instant.now(), "Error: nope");
        }

        List<ManifestEditProposal> proposals = store.proposeEdits(30, 1, 0.15, 5);

        assertEquals(1, proposals.size());
        assertEquals("real-skill", proposals.get(0).manifestKey());
    }

    @Test
    void proposalsSortedWorstQualityFirst() throws SQLException {
        // "mild" — 1 of 3 sessions retries
        insert("mild-manifest", "session-1", Instant.now(), null);
        Instant t0 = Instant.now();
        insert("mild-manifest", "session-2", t0, null);
        insert("mild-manifest", "session-2", t0.plusSeconds(5), null);
        insert("mild-manifest", "session-3", Instant.now(), null);

        // "severe" — all 3 sessions retry
        for (int i = 1; i <= 3; i++) {
            String session = "severe-session-" + i;
            Instant t1 = Instant.now();
            insert("severe-manifest", session, t1, null);
            insert("severe-manifest", session, t1.plusSeconds(5), null);
        }

        List<ManifestEditProposal> proposals = store.proposeEdits(30, 1, 0.05, 5);

        assertTrue(proposals.size() >= 2);
        assertEquals("severe-manifest", proposals.get(0).manifestKey());
        assertTrue(proposals.get(0).qualityScore() >= proposals.get(1).qualityScore());
    }

    @Test
    void evidenceRespectsLimit() throws SQLException {
        for (int i = 1; i <= 8; i++) {
            insert("busy-manifest", "session-" + i, Instant.now(), "Error: nope");
        }

        List<ManifestEditProposal> proposals = store.proposeEdits(30, 1, 0.15, 3);

        assertEquals(1, proposals.size());
        assertEquals(3, proposals.get(0).evidenceSessionIds().size());
        assertEquals(8, proposals.get(0).errorSessions(), "the underlying count should reflect all 8, only evidence is capped");
    }

    private void insert(String manifestKey, String sessionId, Instant ts, String outputPreview) throws SQLException {
        String iso = ts.toString();
        String command = "some-command --for " + manifestKey;
        eventStore.insert(new ToolEvent(
                0, iso, sessionId, "/src/test-project", "Bash",
                command, manifestKey, outputPreview, null, iso
        ));
        // EventStore.insert() never persists output_preview — matches production, where the
        // command event is written first and output is attached separately (PostToolUse hook
        // -> EventStore.updateOutputPreview) once the tool call actually completes.
        if (outputPreview != null) {
            eventStore.updateOutputPreview(sessionId, command, outputPreview);
        }
    }
}
