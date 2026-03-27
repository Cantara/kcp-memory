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
    void parsesClaudeJsonlSessions(@TempDir Path tmp) throws Exception {
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
        assertEquals(3, s.getTurnCount());
        assertEquals(1, s.getToolCallCount());
        assertTrue(s.getToolNames().contains("Read"));
        assertTrue(s.getFiles().contains("/src/myapp/src/auth.ts"));
    }

    /**
     * Regression test for v0.21.0: Claude Code transcripts use type="user" for human messages,
     * not "human". Before the fix, first_message and all_user_text were always NULL for real
     * Claude Code sessions, making FTS return 0 results.
     */
    @Test
    void parsesClaudeJsonlSessions_userType(@TempDir Path tmp) throws Exception {
        String jsonl = """
                {"type":"user","timestamp":"2026-03-01T10:00:00Z","message":{"content":[{"type":"text","text":"How do I add authentication?"}]},"cwd":"/src/myapp","sessionId":"sess-usertype"}
                {"type":"assistant","timestamp":"2026-03-01T10:00:05Z","message":{"model":"claude-sonnet-4-6","content":[{"type":"tool_use","name":"Read","input":{"file_path":"/src/myapp/auth.ts"}}]}}
                {"type":"user","timestamp":"2026-03-01T10:01:00Z","message":{"content":[{"type":"text","text":"Can you also update the tests?"}]}}
                """;

        Path file = tmp.resolve("sess-usertype.jsonl");
        Files.writeString(file, jsonl);

        Optional<SessionParser.ParseResult> result = parser.parse(file, "my-project");
        assertTrue(result.isPresent());

        Session s = result.get().session();
        assertEquals("sess-usertype", s.getSessionId());
        assertEquals("/src/myapp", s.getProjectDir());
        // first_message must NOT be null — this was the v0.21.0 regression
        assertNotNull(s.getFirstMessage(), "first_message must be populated for type=user sessions");
        assertEquals("How do I add authentication?", s.getFirstMessage());
        assertNotNull(s.getAllUserText(), "all_user_text must be populated for type=user sessions");
        assertTrue(s.getAllUserText().contains("tests"), "all_user_text must accumulate all user messages");
    }

    @Test
    void parsesGeminiJsonSessions(@TempDir Path tmp) throws Exception {
        Path chatsDir = Files.createDirectories(tmp.resolve("kcp-dogfood-queue").resolve("chats"));
        Path file = chatsDir.resolve("session-2026-03-11T21-02-test.json");
        String json = """
                {
                  "sessionId": "gemini-session-1",
                  "projectHash": "abc123",
                  "startTime": "2026-03-11T21:02:50.057Z",
                  "lastUpdated": "2026-03-11T21:29:51.750Z",
                  "messages": [
                    {
                      "timestamp": "2026-03-11T21:02:50.057Z",
                      "type": "user",
                      "content": [{"text": "Fix the queue-secret search bug"}]
                    },
                    {
                      "timestamp": "2026-03-11T21:02:53.302Z",
                      "type": "gemini",
                      "content": "I will inspect the scanner and parser.",
                      "toolCalls": [
                        {"name": "read_file", "args": {"file_path": "METRICS.md"}}
                      ]
                    }
                  ]
                }
                """;
        Files.writeString(file, json);

        Optional<SessionParser.ParseResult> result = parser.parse(file, "kcp-dogfood-queue");
        assertTrue(result.isPresent());

        Session s = result.get().session();
        assertEquals("gemini-session-1", s.getSessionId());
        assertEquals("kcp-dogfood-queue", s.getProjectDir());
        assertEquals("gemini", s.getModel());
        assertEquals("Fix the queue-secret search bug", s.getFirstMessage());
        assertEquals(2, s.getTurnCount());
        assertEquals(1, s.getToolCallCount());
        assertTrue(s.getFiles().contains("METRICS.md"));
    }

    @Test
    void parsesCodexJsonlSessions(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("rollout-2026-03-11T22-30-47-test.jsonl");
        String jsonl = """
                {"timestamp":"2026-03-11T21:32:05.669Z","type":"session_meta","payload":{"id":"codex-session-1","timestamp":"2026-03-11T21:30:47.105Z","cwd":"/Users/stiglau/utvikling/opensource/kcp-memory","originator":"codex_cli_rs"}}
                {"timestamp":"2026-03-11T21:32:05.669Z","type":"event_msg","payload":{"type":"user_message","message":"Resolve GitHub Issue #2"}}
                {"timestamp":"2026-03-11T21:32:10.821Z","type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"I’m auditing the codebase."}]}}
                {"timestamp":"2026-03-11T21:32:10.822Z","type":"response_item","payload":{"type":"function_call","name":"exec_command","arguments":"{\\"cmd\\":\\"git status\\"}"}}
                """;
        Files.writeString(file, jsonl);

        Optional<SessionParser.ParseResult> result = parser.parse(file, "kcp-memory");
        assertTrue(result.isPresent());

        Session s = result.get().session();
        assertEquals("codex-session-1", s.getSessionId());
        assertEquals("/Users/stiglau/utvikling/opensource/kcp-memory", s.getProjectDir());
        assertEquals("codex_cli_rs", s.getModel());
        assertEquals("Resolve GitHub Issue #2", s.getFirstMessage());
        assertEquals(2, s.getTurnCount());
        assertEquals(1, s.getToolCallCount());
        assertTrue(s.getToolNames().contains("exec_command"));
    }

    @Test
    void handlesEmptyFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("empty-session.jsonl");
        Files.writeString(file, "");

        Optional<SessionParser.ParseResult> result = parser.parse(file, "slug");
        assertTrue(result.isPresent());
        Session s = result.get().session();
        assertEquals(0, s.getTurnCount());
        assertEquals(0, s.getToolCallCount());
    }
}
