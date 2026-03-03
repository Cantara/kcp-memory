package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.model.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SessionParserTest {

    private final SessionParser parser = new SessionParser();

    @Test
    void parsesHumanAndAssistantTurns(@TempDir Path tmp) throws Exception {
        // Minimal Claude Code JSONL format
        String jsonl = """
                {"type":"human","timestamp":"2026-03-01T10:00:00Z","message":{"content":"How do I add authentication?"},"cwd":"/src/myapp"}
                {"type":"assistant","timestamp":"2026-03-01T10:00:05Z","message":{"model":"claude-sonnet-4-6","content":[{"type":"text","text":"I will help you add authentication."},{"type":"tool_use","name":"Read","input":{"file_path":"/src/myapp/src/auth.ts"}}]}}
                {"type":"human","timestamp":"2026-03-01T10:01:00Z","message":{"content":"Can you also update the tests?"}}
                """;

        Path file = tmp.resolve("sess-abc123.jsonl");
        Files.writeString(file, jsonl);

        Optional<SessionParser.ParseResult> result = parser.parse(file, "my-project");
        assertTrue(result.isPresent());

        Session s = result.get().session();
        assertEquals("sess-abc123", s.getSessionId());
        assertEquals("/src/myapp", s.getProjectDir());
        assertEquals("claude-sonnet-4-6", s.getModel());
        assertEquals("How do I add authentication?", s.getFirstMessage());
        assertEquals(3, s.getTurnCount()); // 2 human turns + 1 assistant turn
        assertEquals(1, s.getToolCallCount()); // 1 tool use
        assertTrue(s.getToolNames().contains("Read"));
        assertTrue(s.getFiles().contains("/src/myapp/src/auth.ts"));
        assertNotNull(s.getScannedAt());
    }

    @Test
    void handlesEmptyFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("empty-session.jsonl");
        Files.writeString(file, "");

        Optional<SessionParser.ParseResult> result = parser.parse(file, "slug");
        // Empty file → no turns, but still returns a session with zero counts
        assertTrue(result.isPresent());
        Session s = result.get().session();
        assertEquals(0, s.getTurnCount());
        assertEquals(0, s.getToolCallCount());
    }

    @Test
    void skipsUnreadableLines(@TempDir Path tmp) throws Exception {
        String jsonl = """
                not valid json
                {"type":"human","timestamp":"2026-03-01T10:00:00Z","message":{"content":"Valid turn"},"cwd":"/src/x"}
                {broken
                """;

        Path file = tmp.resolve("mixed-session.jsonl");
        Files.writeString(file, jsonl);

        Optional<SessionParser.ParseResult> result = parser.parse(file, "x");
        assertTrue(result.isPresent());
        assertEquals(1, result.get().session().getTurnCount()); // only the valid line
    }

    @Test
    void truncatesFirstMessageAt500Chars(@TempDir Path tmp) throws Exception {
        String longMessage = "x".repeat(600);
        String jsonl = String.format(
                "{\"type\":\"human\",\"timestamp\":\"2026-03-01T10:00:00Z\",\"message\":{\"content\":\"%s\"},\"cwd\":\"/src/a\"}%n",
                longMessage);

        Path file = tmp.resolve("long-session.jsonl");
        Files.writeString(file, jsonl);

        Optional<SessionParser.ParseResult> result = parser.parse(file, "a");
        assertTrue(result.isPresent());
        String first = result.get().session().getFirstMessage();
        assertNotNull(first);
        assertTrue(first.length() <= 500);
    }
}
