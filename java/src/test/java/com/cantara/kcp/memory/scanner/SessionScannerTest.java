package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.store.MemoryDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("resource")
class SessionScannerTest {

    private static final String PI_JSONL = """
            {"type":"session","version":3,"id":"pi-session-1","timestamp":"2026-03-01T10:00:00.000Z","cwd":"/src/myapp"}
            {"type":"message","id":"u1","parentId":null,"timestamp":"2026-03-01T10:00:05.000Z","message":{"role":"user","content":[{"type":"text","text":"How do I add authentication?"}]}}
            """;

    @Test
    void scansPiSessionUnderAgentSessionsRoot(@TempDir Path tmp) throws Exception {
        // Mirrors ~/.pi/agent/sessions/<project-slug>/<file>.jsonl — the file itself
        // does NOT start with "rollout-", unlike Codex's convention for the same
        // trailing "sessions" root segment.
        Path root = Files.createDirectories(tmp.resolve("agent").resolve("sessions"));
        Path projectDir = Files.createDirectories(root.resolve("--src-myapp--"));
        Files.writeString(projectDir.resolve("2026-03-01T10-00-00-000Z_pi-session-1.jsonl"), PI_JSONL);

        MemoryDatabase db = new MemoryDatabase(Files.createTempFile("kcp-test-", ".db"));
        SessionScanner.ScanResult result = new SessionScanner(root, db).scan(false);

        assertEquals(1, result.indexed(),
                "a non rollout-prefixed .jsonl file under an agent/sessions root must still be indexed");
        assertFalse(result.hasErrors());
    }

    @Test
    void codexRootStillRejectsNonRolloutFiles(@TempDir Path tmp) throws Exception {
        // Regression guard: the new agent/sessions branch must be checked before the
        // generic "sessions" branch without loosening Codex's own rollout- filter.
        Path root = Files.createDirectories(tmp.resolve("sessions"));
        Files.writeString(root.resolve("not-a-rollout-file.jsonl"), PI_JSONL);

        MemoryDatabase db = new MemoryDatabase(Files.createTempFile("kcp-test-", ".db"));
        SessionScanner.ScanResult result = new SessionScanner(root, db).scan(false);

        assertEquals(0, result.indexed(),
                "a sessions root without the agent/ parent segment must keep requiring the rollout- prefix");
    }
}
