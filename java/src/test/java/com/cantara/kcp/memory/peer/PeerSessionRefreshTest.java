package com.cantara.kcp.memory.peer;

import com.cantara.kcp.memory.handler.IngestHandler;
import com.cantara.kcp.memory.handler.ListHandler;
import com.cantara.kcp.memory.model.Session;
import com.cantara.kcp.memory.server.TcpHttpServer;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.PeerCursorStore;
import com.cantara.kcp.memory.store.SessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #85 follow-up. A peer session used to keep the turn and tool counts it had the first time
 * it was seen: the pull inserted with INSERT OR IGNORE, the peer's /sessions ignored the
 * {@code since} cursor and always returned its newest 100 sessions, and a session was
 * pushed only once. These tests run the real handlers behind a real HTTP server.
 */
class PeerSessionRefreshTest {

    @TempDir Path tmp;
    private MemoryDatabase remoteDb;
    private MemoryDatabase localDb;
    private SessionStore remote;
    private SessionStore local;
    private TcpHttpServer server;
    private PeerSyncService sync;

    private static int freePort() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        remoteDb = new MemoryDatabase(tmp.resolve("remote.db"));
        localDb = new MemoryDatabase(tmp.resolve("local.db"));
        remote = new SessionStore(remoteDb);
        local = new SessionStore(localDb);

        int port = freePort();
        server = new TcpHttpServer(port);
        server.createContext("/sessions", new ListHandler(remoteDb));
        server.createContext("/ingest", new IngestHandler(remoteDb));
        server.start();

        sync = new PeerSyncService(localDb, "tcp://127.0.0.1:" + port, "laptop");
        sync.useForTest("http://127.0.0.1:" + port, "hub");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
        remoteDb.close();
        localDb.close();
    }

    private static Session session(String id, int turns, int tools) {
        Session s = new Session();
        s.setSessionId(id);
        s.setProjectDir("/src/app");
        s.setFirstMessage("first " + id);
        s.setAllUserText("first " + id);
        s.setStartedAt(Instant.now().toString());
        s.setScannedAt(Instant.now().toString());
        s.setTurnCount(turns);
        s.setToolCallCount(tools);
        s.setToolNames(List.of("Read"));
        s.setFiles(List.of());
        return s;
    }

    private static String counts(MemoryDatabase db, String id) throws Exception {
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT turn_count || '|' || tool_call_count FROM sessions WHERE session_id = '" + id + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long count(MemoryDatabase db) throws Exception {
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sessions")) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    @Test
    void pullRefreshesASessionThatGrewOnThePeer() throws Exception {
        remote.upsert(session("s1", 1, 0));       // first seen early in its life
        sync.pullSessions();
        assertEquals("1|0", counts(localDb, "s1"));

        remote.upsert(session("s1", 7, 42));      // the session kept running
        sync.pullSessions();

        assertEquals("7|42", counts(localDb, "s1"));
    }

    @Test
    void pullBringsEveryChangedSessionNotJustTheNewest100() throws Exception {
        for (int i = 0; i < 250; i++) remote.upsert(session(String.format("s%03d", i), 2, 3));

        sync.pullSessions();

        assertEquals(250, count(localDb));
    }

    @Test
    void anOldStartedAtCursorDoesNotHideSessionsAfterAnUpgrade() throws Exception {
        for (int i = 0; i < 3; i++) remote.upsert(session("s" + i, 2, 3));
        // What a pre-change version stored: the started_at of the last session it pulled.
        new PeerCursorStore(localDb).updateSessionCursor("hub", "2099-01-01T00:00:00Z");

        sync.pullSessions();

        assertEquals(3, count(localDb));
    }

    @Test
    void nextPullAfterAnIdlePeerFetchesNothingNew() throws Exception {
        remote.upsert(session("s1", 4, 4));
        sync.pullSessions();
        local.raiseCounts("s1", 4, 4);
        String before = new PeerCursorStore(localDb).getLastSessionTs("hub");

        sync.pullSessions();

        assertNotNull(before);
        assertTrue(before.startsWith("u1:"), before);
        assertEquals(before, new PeerCursorStore(localDb).getLastSessionTs("hub"));
        assertEquals("4|4", counts(localDb, "s1"));
    }

    @Test
    void pullNeverLowersTheCountsOfASessionThisMachineScannedItself() throws Exception {
        local.upsert(session("x", 9, 9));        // indexed from a local transcript
        remote.upsert(session("x", 1, 1));       // the peer holds an older view of it

        sync.pullSessions();

        assertEquals("9|9", counts(localDb, "x"));
    }

    @Test
    void pushSendsAGrownSessionAgain() throws Exception {
        local.upsert(session("p", 1, 0));
        sync.pushSessions();
        assertEquals("1|0", counts(remoteDb, "p"));

        local.upsert(session("p", 5, 10));       // the local scanner indexed it again
        sync.pushSessions();

        assertEquals("5|10", counts(remoteDb, "p"));
    }

    @Test
    void pushSendsEverySessionNotJustTheFirstPage() throws Exception {
        for (int i = 0; i < 250; i++) local.upsert(session(String.format("s%03d", i), 2, 3));

        sync.pushSessions();

        assertEquals(250, count(remoteDb));
    }

    @Test
    void aPeerWithoutTheChangeFeedIsStillMerged() throws Exception {
        // An older peer ignores updated_since and returns its newest sessions without "next".
        int oldPort = freePort();
        TcpHttpServer oldPeer = new TcpHttpServer(oldPort);
        String body = "{\"project\":\"\",\"count\":1,\"sessions\":[{\"sessionId\":\"old1\","
                + "\"startedAt\":\"2026-03-01T10:00:00Z\",\"projectDir\":\"/x\",\"firstMessage\":\"hi\","
                + "\"turnCount\":3,\"toolCallCount\":8}]}";
        oldPeer.createContext("/sessions", ex ->
                ex.sendResponse(200, "application/json", body.getBytes(StandardCharsets.UTF_8)));
        oldPeer.start();
        try {
            PeerSyncService toOld = new PeerSyncService(localDb, "tcp://127.0.0.1:" + oldPort, "laptop");
            toOld.useForTest("http://127.0.0.1:" + oldPort, "old-hub");

            toOld.pullSessions();

            assertEquals("3|8", counts(localDb, "old1"));
            assertNull(new PeerCursorStore(localDb).getLastSessionTs("old-hub"),
                    "there is no cursor to keep when the peer has no change feed");
        } finally {
            oldPeer.stop();
        }
    }
}
