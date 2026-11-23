package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.store.MemoryDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #85: a transcript file can carry more than one {@code sessionId}, for instance a
 * resumed Claude Code session that starts with the lines of the session it was
 * resumed from. The scanner indexes one session per file (the last {@code sessionId}
 * seen), but stamped each tool usage with the {@code sessionId} of its own line.
 *
 * <p>Two things followed. The delete before a re-index only covered the file's own
 * session, so the rows stamped with the earlier session id were never deleted and
 * every re-index of a growing file added another copy of them, and
 * {@code sessions.tool_call_count} no longer matched the rows stored for the session.
 * And when the earlier session was not indexed, the insert violated the foreign key
 * from {@code tool_usages.session_id} to {@code sessions}, so the file failed on every
 * scan and was parsed again each time.
 */
class SessionScannerToolUsageOwnershipTest {

    @TempDir Path tmp;
    private Path projectDir;
    private MemoryDatabase db;

    @BeforeEach
    void setUp() throws Exception {
        projectDir = Files.createDirectories(tmp.resolve("projects").resolve("-src-app"));
        db = new MemoryDatabase(tmp.resolve("memory.db"));
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private static String toolLine(String sessionId, String ts, String path) {
        return "{\"type\":\"assistant\",\"sessionId\":\"" + sessionId + "\",\"timestamp\":\"" + ts
                + "\",\"cwd\":\"/src/app\",\"message\":{\"model\":\"m\",\"content\":[{\"type\":\"tool_use\","
                + "\"id\":\"toolu_" + ts + "\",\"name\":\"Read\",\"input\":{\"file_path\":\"" + path + "\"}}]}}\n";
    }

    private Path write(String name, String content) throws Exception {
        Path file = projectDir.resolve(name + ".jsonl");
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        return file;
    }

    /** Scans the fixture root and fails with the scanner's own messages if any file errored. */
    private SessionScanner.ScanResult scan(boolean force) {
        SessionScanner.ScanResult result =
                new SessionScanner(List.of(projectDir.getParent()), db).scan(force);
        assertEquals(0, result.errors(), () -> "scan errors: " + result.errorMessages());
        return result;
    }

    private long scalar(String sql) throws Exception {
        try (Statement st = db.getConnection().createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private long duplicateKeys() throws Exception {
        return scalar("SELECT COUNT(*) FROM (SELECT 1 FROM tool_usages "
                + "GROUP BY session_id, tool_name, occurred_at, tool_input HAVING COUNT(*) > 1)");
    }

    @Test
    void reindexingAResumedFileDoesNotAddCopiesOfTheEarlierSessionsToolCalls() throws Exception {
        // The original session is indexed. The resumed file starts with its history, then
        // continues under a new session id.
        write("old", toolLine("old", "2026-03-01T10:00:00Z", "/a"));
        scan(false);
        Path resumed = write("new",
                toolLine("old", "2026-03-01T10:00:00Z", "/a")
                        + toolLine("new", "2026-03-01T10:05:00Z", "/b"));
        scan(false);

        for (int i = 0; i < 3; i++) {
            Files.writeString(resumed, toolLine("new", "2026-03-01T11:0" + i + ":00Z", "/c" + i),
                    StandardOpenOption.APPEND);
            Files.setLastModifiedTime(resumed, FileTime.fromMillis(System.currentTimeMillis() + 5_000L * (i + 1)));
            assertEquals(1, scan(false).indexed(), "only the grown file is re-indexed");
        }

        assertEquals(0, duplicateKeys());
        assertEquals(1, scalar("SELECT COUNT(*) FROM tool_usages WHERE session_id = 'old'"),
                "the original session keeps exactly the rows of its own file");
        assertEquals(5, scalar("SELECT COUNT(*) FROM tool_usages WHERE session_id = 'new'"),
                "history copy + new call + 3 appended calls, each stored once");
    }

    @Test
    void aResumedFileIsIndexedEvenWhenTheEarlierSessionIsNotIndexed() throws Exception {
        write("new",
                toolLine("old", "2026-03-01T10:00:00Z", "/a")
                        + toolLine("new", "2026-03-01T10:05:00Z", "/b"));

        assertEquals(1, scan(false).indexed(), "no foreign key error from a session id that is not indexed");
        assertEquals(2, scalar("SELECT COUNT(*) FROM tool_usages WHERE session_id = 'new'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM sessions WHERE session_id = 'old'"),
                "the file is indexed as one session, the last id it carries");
    }

    @Test
    void toolCallCountEqualsTheRowsStoredForTheSession() throws Exception {
        write("new",
                toolLine("old", "2026-03-01T10:00:00Z", "/a")
                        + toolLine("new", "2026-03-01T10:05:00Z", "/b")
                        + toolLine("new", "2026-03-01T10:06:00Z", "/c"));
        scan(false);

        assertEquals(3, scalar("SELECT tool_call_count FROM sessions WHERE session_id = 'new'"));
        assertEquals(3, scalar("SELECT COUNT(*) FROM tool_usages WHERE session_id = 'new'"),
                "every tool call counted for the session is stored under it");
    }

    @Test
    void forcedScansOfAnOriginalAndItsResumedCopyLeaveNoDuplicateKeys() throws Exception {
        write("old", toolLine("old", "2026-03-01T10:00:00Z", "/a"));
        write("new",
                toolLine("old", "2026-03-01T10:00:00Z", "/a")
                        + toolLine("new", "2026-03-01T10:05:00Z", "/b"));

        scan(true);
        scan(true);

        assertEquals(0, duplicateKeys());
        assertEquals(1, scalar("SELECT COUNT(*) FROM tool_usages WHERE session_id = 'old'"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM tool_usages WHERE session_id = 'new'"));
    }
}
