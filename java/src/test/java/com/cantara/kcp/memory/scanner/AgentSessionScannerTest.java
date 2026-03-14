package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.model.AgentSession;
import com.cantara.kcp.memory.store.AgentSessionStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("resource")

class AgentSessionScannerTest {

    // Synthetic subagent JSONL — each line has isSidechain, sessionId (parent), agentId, slug, cwd
    private static final String SUBAGENT_JSONL = """
            {"isSidechain":true,"sessionId":"624a7854-a7d2-4331-8ddb-7dab21e7064c","agentId":"ae5fb06d98fb195b2","slug":"tingly-soaring-naur","cwd":"/src/exoreaction","type":"user","message":{"content":"You are filling skills gaps for the eXOReaction ecosystem."}}
            {"isSidechain":true,"sessionId":"624a7854-a7d2-4331-8ddb-7dab21e7064c","agentId":"ae5fb06d98fb195b2","slug":"tingly-soaring-naur","cwd":"/src/exoreaction","type":"assistant","message":{"model":"claude-opus-4-6","content":[{"type":"text","text":"I will investigate the co-events repos first."},{"type":"tool_use","name":"Glob","input":{}}]}}
            {"isSidechain":true,"sessionId":"624a7854-a7d2-4331-8ddb-7dab21e7064c","agentId":"ae5fb06d98fb195b2","slug":"tingly-soaring-naur","cwd":"/src/exoreaction","type":"user","message":{"content":[{"type":"tool_result","content":"result"}]}}
            """;

    @Test
    void detectsSubagentFile(@TempDir Path tmp) throws Exception {
        Path subagentsDir = Files.createDirectories(
                tmp.resolve("624a7854-a7d2-4331-8ddb-7dab21e7064c").resolve("subagents"));
        Path file = subagentsDir.resolve("agent-ae5fb06d98fb195b2.jsonl");
        Files.writeString(file, SUBAGENT_JSONL);

        assertTrue(AgentSessionScanner.isSubagentFile(file),
                "File inside subagents/ starting with agent- should be detected");
    }

    @Test
    void rejectsRegularSessionFile(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("624a7854-a7d2-4331-8ddb-7dab21e7064c.jsonl");
        Files.writeString(file, "{\"type\":\"user\",\"message\":{\"content\":\"hello\"}}");

        assertFalse(AgentSessionScanner.isSubagentFile(file),
                "Regular session file should not be detected as subagent");
    }

    @Test
    void parsesSubagentFields(@TempDir Path tmp) throws Exception {
        Path subagentsDir = Files.createDirectories(
                tmp.resolve("624a7854-a7d2-4331-8ddb-7dab21e7064c").resolve("subagents"));
        Path file = subagentsDir.resolve("agent-ae5fb06d98fb195b2.jsonl");
        Files.writeString(file, SUBAGENT_JSONL);

        AgentSessionScanner scanner = new AgentSessionScanner(tmp, new MemoryDatabase(Files.createTempFile("kcp-test-", ".db")));
        AgentSession agent = scanner.parseAgentFile(file);

        assertNotNull(agent);
        assertEquals("ae5fb06d98fb195b2", agent.getAgentId());
        assertEquals("624a7854-a7d2-4331-8ddb-7dab21e7064c", agent.getParentSessionId());
        assertEquals("tingly-soaring-naur", agent.getAgentSlug());
        assertEquals("/src/exoreaction", agent.getCwd());
        assertNotNull(agent.getFirstMessage());
        assertTrue(agent.getFirstMessage().contains("eXOReaction ecosystem"),
                "First message should be the task prompt");
        // Tool call from assistant message
        assertEquals(1, agent.getToolCallCount());
        assertTrue(agent.getToolNames().contains("Glob"));
        // turnCount = user prompts + assistant turns; tool_result user messages are excluded
        // Line 1: user prompt (+1), Line 2: assistant (+1), Line 3: tool_result (excluded) = 2
        assertEquals(2, agent.getTurnCount(), "User prompt + assistant response = 2 turns");
    }

    @Test
    void scanIndexesAgentFile(@TempDir Path tmp) throws Exception {
        Path subagentsDir = Files.createDirectories(
                tmp.resolve("624a7854-a7d2-4331-8ddb-7dab21e7064c").resolve("subagents"));
        Files.writeString(subagentsDir.resolve("agent-ae5fb06d98fb195b2.jsonl"), SUBAGENT_JSONL);

        MemoryDatabase db = new MemoryDatabase(Files.createTempFile("kcp-test-", ".db"));
        AgentSessionScanner scanner = new AgentSessionScanner(tmp, db);
        AgentSessionScanner.ScanResult result = scanner.scan(false);

        assertEquals(1, result.indexed());
        assertEquals(0, result.errors());

        List<AgentSession> indexed = new AgentSessionStore(db).listByParent(
                "624a7854-a7d2-4331-8ddb-7dab21e7064c", 10);
        assertEquals(1, indexed.size());
        assertEquals("ae5fb06d98fb195b2", indexed.get(0).getAgentId());
    }

    @Test
    void skipsNonSubagentFiles(@TempDir Path tmp) throws Exception {
        // Regular session file — should be ignored
        Path regularFile = tmp.resolve("some-session.jsonl");
        Files.writeString(regularFile, SUBAGENT_JSONL);

        MemoryDatabase db = new MemoryDatabase(Files.createTempFile("kcp-test-", ".db"));
        AgentSessionScanner scanner = new AgentSessionScanner(tmp, db);
        AgentSessionScanner.ScanResult result = scanner.scan(false);

        assertEquals(0, result.indexed(), "Regular session files should not be indexed by AgentSessionScanner");
    }
}
