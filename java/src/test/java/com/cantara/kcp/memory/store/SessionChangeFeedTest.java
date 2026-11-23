package com.cantara.kcp.memory.store;

import com.cantara.kcp.memory.model.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Peer sync (#85 follow-up): the change feed pages through sessions by (updated_at, session_id),
 * and {@code raiseCounts} lets a peer session catch up with the session it summarises.
 */
class SessionChangeFeedTest {

    @TempDir Path tmp;
    private MemoryDatabase db;
    private SessionStore store;

    @BeforeEach
    void setUp() throws Exception {
        db = new MemoryDatabase(tmp.resolve("memory.db"));
        store = new SessionStore(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
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

    private void exec(String sql, String... args) throws Exception {
        try (PreparedStatement ps = db.getConnection().prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setString(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    private String text(String sql) throws Exception {
        try (Statement st = db.getConnection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static List<String> ids(SessionStore.ChangePage page) {
        return page.sessions().stream().map(c -> c.session().getSessionId()).toList();
    }

    @Test
    void upsertStampsUpdatedAtInTheCanonicalForm() throws Exception {
        store.upsert(session("a", 1, 0));
        String stamp = text("SELECT updated_at FROM sessions WHERE session_id = 'a'");
        assertNotNull(stamp);
        assertTrue(stamp.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"), stamp);
    }

    @Test
    void feedListsSessionsInOrderOfChangeAndResumesAfterTheCursor() throws Exception {
        for (String id : List.of("a", "b", "c")) store.upsert(session(id, 1, 0));
        exec("UPDATE sessions SET updated_at = '2026-01-01T00:00:03.000Z' WHERE session_id = 'a'");
        exec("UPDATE sessions SET updated_at = '2026-01-01T00:00:01.000Z' WHERE session_id = 'b'");
        exec("UPDATE sessions SET updated_at = '2026-01-01T00:00:02.000Z' WHERE session_id = 'c'");

        SessionStore.ChangePage first = store.changesSince(null, 2, false);
        assertEquals(List.of("b", "c"), ids(first));
        assertEquals("2026-01-01T00:00:02.000Z|c", first.next());

        SessionStore.ChangePage second = store.changesSince(first.next(), 2, false);
        assertEquals(List.of("a"), ids(second));

        SessionStore.ChangePage empty = store.changesSince(second.next(), 2, false);
        assertTrue(empty.sessions().isEmpty());
        assertEquals(second.next(), empty.next(), "an empty page keeps the cursor it was given");
    }

    @Test
    void rowsWithTheSameStampArePagedWithoutSkipsOrRepeats() throws Exception {
        for (int i = 1; i <= 5; i++) store.upsert(session("s" + i, 1, 0));
        exec("UPDATE sessions SET updated_at = '2026-01-01T00:00:00.000Z'");

        List<String> seen = new ArrayList<>();
        String cursor = "";
        for (int guard = 0; guard < 10; guard++) {
            SessionStore.ChangePage page = store.changesSince(cursor, 2, false);
            if (page.sessions().isEmpty()) break;
            seen.addAll(ids(page));
            cursor = page.next();
        }
        assertEquals(List.of("s1", "s2", "s3", "s4", "s5"), seen);
    }

    @Test
    void aSessionThatChangesAgainReappearsAfterTheCursor() throws Exception {
        store.upsert(session("a", 1, 0));
        store.upsert(session("b", 1, 0));
        exec("UPDATE sessions SET updated_at = '2026-01-01T00:00:00.000Z'");
        String cursor = store.changesSince(null, 10, false).next();

        store.upsert(session("a", 9, 20));   // the scanner indexed it again

        SessionStore.ChangePage page = store.changesSince(cursor, 10, false);
        assertEquals(List.of("a"), ids(page));
        assertEquals(9, page.sessions().get(0).session().getTurnCount());
    }

    @Test
    void aRowWithoutAStampSortsFirstInsteadOfBeingLost() throws Exception {
        store.upsert(session("a", 1, 0));
        store.upsert(session("b", 1, 0));
        exec("UPDATE sessions SET updated_at = NULL WHERE session_id = 'a'");

        assertEquals(List.of("a", "b"), ids(store.changesSince(null, 10, false)));
    }

    @Test
    void recallGateSkipsForgottenMemoriesOnlyWhenAsked() throws Exception {
        store.upsert(session("a", 1, 0));
        store.upsert(session("b", 1, 0));
        assertTrue(store.forget("a", "test"));

        assertEquals(List.of("b"), ids(store.changesSince(null, 10, true)));
        assertEquals(List.of("a", "b"), ids(store.changesSince(null, 10, false)).stream().sorted().toList());
    }

    @Test
    void feedCarriesTheSourceInstance() throws Exception {
        store.upsert(session("a", 1, 0));
        exec("UPDATE sessions SET source_instance = 'laptop' WHERE session_id = 'a'");
        assertEquals("laptop", store.changesSince(null, 10, false).sessions().get(0).sourceInstance());
    }

    @Test
    void raiseCountsRaisesButNeverLowers() throws Exception {
        store.upsert(session("a", 1, 0));

        assertTrue(store.raiseCounts("a", 5, 10));
        assertEquals("5|10", text("SELECT turn_count || '|' || tool_call_count FROM sessions WHERE session_id = 'a'"));

        String stamp = text("SELECT updated_at FROM sessions WHERE session_id = 'a'");
        assertFalse(store.raiseCounts("a", 3, 4), "a lower or equal copy changes nothing");
        assertEquals("5|10", text("SELECT turn_count || '|' || tool_call_count FROM sessions WHERE session_id = 'a'"));
        assertEquals(stamp, text("SELECT updated_at FROM sessions WHERE session_id = 'a'"),
                "an unchanged row is not marked as changed, so two peers cannot keep re-sending it");

        assertTrue(store.raiseCounts("a", 5, 11), "one count growing is enough");
        assertEquals("5|11", text("SELECT turn_count || '|' || tool_call_count FROM sessions WHERE session_id = 'a'"));
    }

    @Test
    void raiseCountsChangesNothingButTheCountsAndTheStamp() throws Exception {
        store.upsert(session("a", 1, 0));
        exec("UPDATE sessions SET source_instance = 'hub' WHERE session_id = 'a'");

        store.raiseCounts("a", 7, 7);

        assertEquals("first a", text("SELECT first_message FROM sessions WHERE session_id = 'a'"));
        assertEquals("hub", text("SELECT source_instance FROM sessions WHERE session_id = 'a'"));
        assertEquals("first a", text("SELECT all_user_text FROM sessions WHERE session_id = 'a'"));
    }
}
