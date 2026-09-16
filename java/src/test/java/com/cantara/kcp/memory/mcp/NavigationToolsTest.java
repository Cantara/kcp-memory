package com.cantara.kcp.memory.mcp;

import com.cantara.kcp.memory.model.AgentSession;
import com.cantara.kcp.memory.model.Decision;
import com.cantara.kcp.memory.model.Session;
import com.cantara.kcp.memory.model.ToolEvent;
import com.cantara.kcp.memory.store.AgentSessionStore;
import com.cantara.kcp.memory.store.DecisionStore;
import com.cantara.kcp.memory.store.EventStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers kcp_memory_ls / kcp_memory_tree / kcp_memory_read / kcp_memory_nav_history
 * (the NavigationTools class). Seeds the same stores McpServer's existing
 * tools already use, then drives NavigationTools directly the same way
 * AgentSessionStoreTest/SessionStoreTest drive their stores directly —
 * McpServer.callTool's JSON-RPC envelope is not the unit under test.
 */
class NavigationToolsTest {

    private Path tempDb;
    private MemoryDatabase db;
    private NavigationTools nav;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-nav-tools-test-", ".db");
        db     = new MemoryDatabase(tempDb);
        nav    = new NavigationTools(db);

        // Session with a subagent — should list as a "directory".
        Session s1 = new Session();
        s1.setSessionId("sess-aaa111");
        s1.setProjectDir("/src/proj-a");
        s1.setStartedAt("2026-01-01T10:00:00Z");
        s1.setTurnCount(3);
        s1.setToolCallCount(5);
        s1.setToolNames(List.of("Read", "Bash"));
        s1.setFiles(List.of("a.java", "b.java"));
        s1.setFirstMessage("Investigate OAuth flow");
        s1.setAllUserText("Investigate OAuth flow");
        s1.setScannedAt("2026-01-01T10:05:00Z");
        new SessionStore(db).upsert(s1);

        // Session with no subagents — should list as a "file".
        Session s2 = new Session();
        s2.setSessionId("sess-bbb222");
        s2.setProjectDir("/src/proj-b");
        s2.setStartedAt("2026-02-01T09:00:00Z");
        s2.setFirstMessage("Second session, no agents");
        s2.setAllUserText("Second session, no agents");
        s2.setScannedAt("2026-02-01T09:05:00Z");
        new SessionStore(db).upsert(s2);

        AgentSession a1 = new AgentSession();
        a1.setAgentId("agent-001");
        a1.setParentSessionId("sess-aaa111");
        a1.setCwd("/src/proj-a");
        a1.setProjectDir("/src/proj-a");
        a1.setFirstMessage("Subtask: find auth code");
        a1.setAllUserText("Subtask: find auth code");
        a1.setTurnCount(2);
        a1.setToolCallCount(4);
        a1.setToolNames(List.of("Grep"));
        a1.setFirstSeenAt("2026-01-01T10:05:00Z");
        a1.setLastUpdatedAt("2026-01-01T10:10:00Z");
        a1.setMessageCount(6);
        a1.setScannedAt("2026-01-01T10:11:00Z");
        new AgentSessionStore(db).upsert(a1);

        Decision d1 = new Decision(
                "dec-001", "decision", "testing", "Use JUnit5", "standard for this codebase",
                List.of("TestNG"), "sess-aaa111", null, List.of("testing", "junit"), "/src/proj-a");
        new DecisionStore(db).upsert(d1);

        new EventStore(db).insert(new ToolEvent(
                0, "2026-01-01T10:00:00Z", "sess-aaa111", "/src/proj-a",
                "Bash", "mvn test", null, null, null, "2026-01-01T10:00:01Z"));
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    private ObjectNode args() {
        return mapper.createObjectNode();
    }

    // ------------------------------------------------------------------
    // kcp_memory_ls
    // ------------------------------------------------------------------

    @Test
    void lsRootListsFourTopLevelDirectories() throws Exception {
        String result = nav.ls(args());
        assertTrue(result.contains("d /sessions"),  result);
        assertTrue(result.contains("d /decisions"), result);
        assertTrue(result.contains("d /events"),    result);
        assertTrue(result.contains("d /subagents"), result);
    }

    @Test
    void lsSessionsMarksDirectoryVsFileByChildren() throws Exception {
        ObjectNode a = args();
        a.put("path", "/sessions");
        String result = nav.ls(a);

        assertTrue(result.contains("d /sessions/sess-aaa111"), "has a subagent -> directory: " + result);
        assertTrue(result.contains("- /sessions/sess-bbb222"), "no subagents -> file: " + result);
    }

    @Test
    void lsSessionChildrenListsItsSubagent() throws Exception {
        ObjectNode a = args();
        a.put("path", "/sessions/sess-aaa111");
        String result = nav.ls(a);

        assertTrue(result.contains("/sessions/sess-aaa111/agent-001"), result);
        assertTrue(result.contains("sub of sess-aaa"), result); // shortId(parentSessionId)
    }

    @Test
    void lsOnLeafPathListsJustThatEntry() throws Exception {
        ObjectNode a = args();
        a.put("path", "/sessions/sess-aaa111/agent-001");
        String result = nav.ls(a);

        assertTrue(result.contains("- /sessions/sess-aaa111/agent-001"), result);
        assertTrue(result.contains("(1 entry)"), result);
    }

    @Test
    void lsUnknownTopLevelPathReturnsDescriptiveMessage() throws Exception {
        ObjectNode a = args();
        a.put("path", "/bogus");
        String result = nav.ls(a);

        assertTrue(result.contains("No such path"), result);
        assertTrue(result.contains("/bogus"), result);
    }

    // ------------------------------------------------------------------
    // kcp_memory_tree
    // ------------------------------------------------------------------

    @Test
    void treeDepthIsClampedToHardCap() throws Exception {
        ObjectNode a = args();
        a.put("path", "/");
        a.put("depth", 100); // far above NavigationTools.MAX_TREE_DEPTH
        String result = nav.tree(a);
        assertNotNull(result);

        // The clamped depth isn't printed in the tree body itself, but every
        // tree call logs its (clamped) depth to the trajectory — read it back.
        String history = nav.navHistory(args());
        assertTrue(history.contains("depth=" + NavigationTools.MAX_TREE_DEPTH),
                "expected depth clamped to " + NavigationTools.MAX_TREE_DEPTH + ": " + history);
    }

    @Test
    void treeAtRootShowsSessionAndItsSubagentNested() throws Exception {
        ObjectNode a = args();
        a.put("path", "/sessions");
        a.put("depth", 2);
        String result = nav.tree(a);

        assertTrue(result.contains("/sessions/sess-aaa111"), result);
        assertTrue(result.contains("/sessions/sess-aaa111/agent-001"), result);
    }

    // ------------------------------------------------------------------
    // kcp_memory_read
    // ------------------------------------------------------------------

    @Test
    void readSessionDetailReturnsFullContent() throws Exception {
        ObjectNode a = args();
        a.put("path", "/sessions/sess-aaa111");
        String result = nav.read(a);

        assertTrue(result.contains("Session: sess-aaa111"), result);
        assertTrue(result.contains("/src/proj-a"), result);
        assertTrue(result.contains("Investigate OAuth flow"), result);
    }

    @Test
    void readSubagentDetailReturnsFullContent() throws Exception {
        ObjectNode a = args();
        a.put("path", "/subagents/agent-001");
        String result = nav.read(a);

        assertTrue(result.contains("Subagent: agent-001"), result);
        assertTrue(result.contains("Parent session: sess-aaa111"), result);
        assertTrue(result.contains("Subtask: find auth code"), result);
    }

    @Test
    void readDecisionDetailReturnsFullContent() throws Exception {
        ObjectNode a = args();
        a.put("path", "/decisions/dec-001");
        String result = nav.read(a);

        assertTrue(result.contains("## dec-001"), result);
        assertTrue(result.contains("**What**: Use JUnit5"), result);
    }

    @Test
    void readEventDetailReturnsFullContent() throws Exception {
        List<ToolEvent> events = new EventStore(db).list(null, 10);
        assertEquals(1, events.size());
        long eventId = events.get(0).id();

        ObjectNode a = args();
        a.put("path", "/events/" + eventId);
        String result = nav.read(a);

        assertTrue(result.contains("Event #" + eventId), result);
        assertTrue(result.contains("mvn test"), result);
    }

    @Test
    void readUnknownSessionReturnsNotFoundMessage() throws Exception {
        ObjectNode a = args();
        a.put("path", "/sessions/does-not-exist");
        String result = nav.read(a);

        assertTrue(result.contains("No such session: does-not-exist"), result);
    }

    // ------------------------------------------------------------------
    // Trajectory logging + kcp_memory_nav_history
    // ------------------------------------------------------------------

    @Test
    void everyLsTreeReadCallIsRecordedAndRetrievable() throws Exception {
        ObjectNode lsArgs = args();
        lsArgs.put("path", "/sessions");
        lsArgs.put("session_id", "corr-123");
        nav.ls(lsArgs);

        ObjectNode treeArgs = args();
        treeArgs.put("path", "/sessions");
        treeArgs.put("session_id", "corr-123");
        nav.tree(treeArgs);

        ObjectNode readArgs = args();
        readArgs.put("path", "/sessions/sess-aaa111");
        readArgs.put("session_id", "corr-123");
        nav.read(readArgs);

        ObjectNode historyArgs = args();
        historyArgs.put("session_id", "corr-123");
        String history = nav.navHistory(historyArgs);

        assertTrue(history.contains("[ls]"),   history);
        assertTrue(history.contains("[tree]"), history);
        assertTrue(history.contains("[read]"), history);
        assertTrue(history.contains("3 navigation call(s)"), history);
    }

    @Test
    void navHistoryFilterBySessionIdExcludesOtherCalls() throws Exception {
        ObjectNode taggedArgs = args();
        taggedArgs.put("path", "/sessions");
        taggedArgs.put("session_id", "only-this-one");
        nav.ls(taggedArgs);

        ObjectNode untaggedArgs = args();
        untaggedArgs.put("path", "/decisions");
        nav.ls(untaggedArgs);

        ObjectNode historyArgs = args();
        historyArgs.put("session_id", "only-this-one");
        String history = nav.navHistory(historyArgs);

        assertTrue(history.contains("1 navigation call(s)"), history);
        assertTrue(history.contains("/sessions"), history);
        assertFalse(history.contains("/decisions"), history);
    }

    @Test
    void navHistoryWithNoRecordedCallsSaysSo() throws Exception {
        String history = nav.navHistory(args());
        assertEquals("No navigation history recorded yet.", history);
    }
}
