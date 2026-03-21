package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.model.ToolEvent;
import com.cantara.kcp.memory.store.EventStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventLogScannerOutputTest {

    private static final String EVENTS_JSONL = """
            {"ts":"2026-03-21T10:00:00Z","session_id":"sess-abc","project_dir":"/src/myapp","tool":"Bash","command":"mvn test","manifest_key":null}
            {"type":"output","ts":"2026-03-21T10:00:05Z","session_id":"sess-abc","tool":"Bash","command":"mvn test","output_preview":"BUILD SUCCESS\\n  Tests run: 42"}
            """;

    private static final String LONG_OUTPUT_JSONL = """
            {"ts":"2026-03-21T11:00:00Z","session_id":"sess-xyz","project_dir":"/src/myapp","tool":"Bash","command":"cat bigfile.txt","manifest_key":null}
            {"type":"output","ts":"2026-03-21T11:00:01Z","session_id":"sess-xyz","tool":"Bash","command":"cat bigfile.txt","output_preview":"%s"}
            """.formatted("A".repeat(250));

    @Test
    void outputPreviewStoredAfterScan(@TempDir Path tmp) throws Exception {
        Path eventsFile = tmp.resolve("events.jsonl");
        Files.writeString(eventsFile, EVENTS_JSONL);

        try (MemoryDatabase db = new MemoryDatabase(tmp.resolve("memory.db"))) {
            new EventLogScanner(db, eventsFile).scan();

            List<ToolEvent> events = new EventStore(db).list(null, 10);
            assertEquals(1, events.size(), "Only one command event should be stored");
            assertEquals("BUILD SUCCESS\n  Tests run: 42", events.get(0).outputPreview());
        }
    }

    @Test
    void outputPreviewTruncatedAt200Chars(@TempDir Path tmp) throws Exception {
        Path eventsFile = tmp.resolve("events.jsonl");
        Files.writeString(eventsFile, LONG_OUTPUT_JSONL);

        try (MemoryDatabase db = new MemoryDatabase(tmp.resolve("memory.db"))) {
            new EventLogScanner(db, eventsFile).scan();

            List<ToolEvent> events = new EventStore(db).list(null, 10);
            assertEquals(1, events.size());
            String preview = events.get(0).outputPreview();
            assertNotNull(preview);
            assertTrue(preview.length() <= 203, // 200 chars + possible "…" suffix
                    "Preview should be truncated, got length: " + preview.length());
        }
    }

    @Test
    void missingOutputLeavesPreviewNull(@TempDir Path tmp) throws Exception {
        String noOutput = """
                {"ts":"2026-03-21T12:00:00Z","session_id":"sess-123","project_dir":"/src/myapp","tool":"Bash","command":"ls","manifest_key":null}
                """;
        Path eventsFile = tmp.resolve("events.jsonl");
        Files.writeString(eventsFile, noOutput);

        try (MemoryDatabase db = new MemoryDatabase(tmp.resolve("memory.db"))) {
            new EventLogScanner(db, eventsFile).scan();

            List<ToolEvent> events = new EventStore(db).list(null, 10);
            assertEquals(1, events.size());
            assertNull(events.get(0).outputPreview(), "No output line → preview should be null");
        }
    }
}
