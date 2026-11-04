package com.cantara.kcp.memory.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** #88 review: state rows are per (path, scanner), not per path. */
class ScanFileStateStoreTest {

    @Test
    void sessionAndAgentScannerKeepSeparateRowsForTheSamePath(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("x.jsonl"), "{}\n");
        try (MemoryDatabase db = new MemoryDatabase(tmp.resolve("memory.db"))) {
            ScanFileStateStore store = new ScanFileStateStore(db);
            ScanFileStateStore.FileStamp stamp = ScanFileStateStore.FileStamp.of(file);

            store.record(ScanFileStateStore.SCANNER_SESSION, file, "session-1", stamp);
            store.record(ScanFileStateStore.SCANNER_AGENT, file, "agent-1", stamp);

            ScanFileStateStore.Entry session = store.get(ScanFileStateStore.SCANNER_SESSION, file);
            ScanFileStateStore.Entry agent = store.get(ScanFileStateStore.SCANNER_AGENT, file);
            assertNotNull(session, "the agent scanner's row must not overwrite the session scanner's");
            assertEquals("session-1", session.entityId());
            assertNotNull(agent);
            assertEquals("agent-1", agent.entityId());
        }
    }
}
