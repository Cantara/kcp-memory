package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.ManifestQualityRecord;
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

class ManifestQualityStoreTest {

    private Path tempDb;
    private MemoryDatabase db;
    private EventStore eventStore;
    private ManifestQualityStore store;

    @BeforeEach
    void setUp() throws Exception {
        tempDb     = Files.createTempFile("kcp-quality-test-", ".db");
        db         = new MemoryDatabase(tempDb);
        eventStore = new EventStore(db);
        store      = new ManifestQualityStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    @Test
    void analyzeIncludesRealSkillManifest() throws SQLException {
        insert("real-skill", "session-1");
        insert("real-skill", "session-2");

        List<ManifestQualityRecord> records = store.analyze(30, 1, 20);

        assertEquals(1, records.size());
        assertEquals("real-skill", records.get(0).manifestKey());
    }

    @Test
    void analyzeExcludesSuppressedMarker() throws SQLException {
        // kcp-commands writes "SUPPRESSED" for commands it filtered — not an authored
        // skill, never a candidate for "improve this manifest" reporting.
        insert("SUPPRESSED", "session-1");
        insert("SUPPRESSED", "session-2");

        List<ManifestQualityRecord> records = store.analyze(30, 1, 20);

        assertTrue(records.isEmpty());
    }

    @Test
    void analyzeExcludesFilterPrefixedKeys() throws SQLException {
        insert("FILTER:grep", "session-1");
        insert("FILTER:ls", "session-2");

        List<ManifestQualityRecord> records = store.analyze(30, 1, 20);

        assertTrue(records.isEmpty());
    }

    @Test
    void countManifestsExcludesNonSkillKeys() throws SQLException {
        insert("real-skill", "session-1");
        insert("SUPPRESSED", "session-2");
        insert("FILTER:grep", "session-3");

        assertEquals(1, store.countManifests());
    }

    @Test
    void countManifestCallsExcludesNonSkillKeys() throws SQLException {
        insert("real-skill", "session-1");
        insert("real-skill", "session-2");
        insert("SUPPRESSED", "session-3");

        assertEquals(2, store.countManifestCalls());
    }

    @Test
    void analyzeByVersionExcludesNonSkillKeys() throws SQLException {
        insert("SUPPRESSED", "session-1");
        insert("real-skill", "session-1");

        var records = store.analyzeByVersion(30, 1);

        assertEquals(1, records.size());
        assertEquals("real-skill", records.get(0).manifestKey());
    }

    private void insert(String manifestKey, String sessionId) throws SQLException {
        String iso = Instant.now().toString();
        eventStore.insert(new ToolEvent(
                0, iso, sessionId, "/src/test-project", "Bash",
                "some-command --for " + manifestKey,
                manifestKey, null, null, iso
        ));
    }
}
