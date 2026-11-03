package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.model.Session;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #88: a scan must decide "unchanged" from size/mtime before parsing, and two scans
 * must not run in parallel. Before the fix every scan parsed every file, and the
 * up-to-date check ran only after the parse.
 */
class SessionScannerIncrementalTest {

    @TempDir Path tmp;
    private Path root;
    private Path dbFile;
    private MemoryDatabase db;

    /** Counts parse() calls and how many run at the same time. */
    static class CountingParser extends SessionParser {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        volatile CountDownLatch blockFirstCall;

        @Override
        public Optional<ParseResult> parse(Path sessionPath, String slug) {
            int n = calls.incrementAndGet();
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            try {
                CountDownLatch latch = blockFirstCall;
                if (n == 1 && latch != null) {
                    try {
                        latch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.parse(sessionPath, slug);
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("projects"));
        dbFile = tmp.resolve("memory.db");
        db = new MemoryDatabase(dbFile);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private Path writeSession(String id, String userText) throws Exception {
        Path dir = Files.createDirectories(root.resolve("-src-app"));
        Path file = dir.resolve(id + ".jsonl");
        Files.writeString(file, claudeLine(id, userText, "2026-03-01T10:00:00Z"));
        // An mtime safely in the past, so "mtime <= scanned_at" is unambiguous.
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        return file;
    }

    private static String claudeLine(String id, String userText, String ts) {
        return "{\"type\":\"user\",\"sessionId\":\"" + id + "\",\"timestamp\":\"" + ts
                + "\",\"cwd\":\"/src/app\",\"message\":{\"content\":\"" + userText + "\"}}\n";
    }

    private SessionScanner scanner(CountingParser parser) {
        return new SessionScanner(List.of(root), db, parser);
    }

    @Test
    void secondScanOverUnchangedFilesParsesNoFiles() throws Exception {
        writeSession("s1", "first");
        writeSession("s2", "second");
        writeSession("s3", "third");

        CountingParser parser = new CountingParser();
        SessionScanner.ScanResult first = scanner(parser).scan(false);
        assertEquals(3, first.indexed());
        assertEquals(3, first.parsed());
        assertEquals(3, parser.calls.get());

        SessionScanner.ScanResult second = scanner(parser).scan(false);
        assertEquals(0, second.indexed());
        assertEquals(3, second.skipped());
        assertEquals(0, second.parsed(), "unchanged files must be skipped without being parsed");
        assertEquals(3, parser.calls.get(), "second scan must not call the parser at all");
    }

    @Test
    void unchangedCheckSurvivesARestart() throws Exception {
        writeSession("s1", "first");
        writeSession("s2", "second");
        scanner(new CountingParser()).scan(false);

        // A new daemon process: new DB connection, new scanner, same DB file.
        db.close();
        db = new MemoryDatabase(dbFile);
        CountingParser parser = new CountingParser();
        SessionScanner.ScanResult afterRestart = scanner(parser).scan(false);

        assertEquals(0, afterRestart.parsed(), "the startup scan must not re-parse unchanged files");
        assertEquals(0, parser.calls.get());
    }

    @Test
    void grownFileIsParsedAgainAndOnlyThatOne() throws Exception {
        writeSession("s1", "first");
        Path growing = writeSession("s2", "second");
        scanner(new CountingParser()).scan(false);

        Files.writeString(growing, claudeLine("s2", "appended later", "2026-03-01T10:05:00Z"),
                StandardOpenOption.APPEND);
        Files.setLastModifiedTime(growing, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        CountingParser parser = new CountingParser();
        SessionScanner.ScanResult result = scanner(parser).scan(false);
        assertEquals(1, result.parsed());
        assertEquals(1, result.indexed());

        Session s2 = new SessionStore(db).getById("s2");
        assertTrue(s2.getAllUserText().contains("appended later"), "the appended line must be indexed");
    }

    @Test
    void sameSizeButNewerMtimeIsParsedAgain() throws Exception {
        Path file = writeSession("s1", "aaaa");
        scanner(new CountingParser()).scan(false);

        Files.writeString(file, claudeLine("s1", "bbbb", "2026-03-01T10:00:00Z"));
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));

        CountingParser parser = new CountingParser();
        assertEquals(1, scanner(parser).scan(false).parsed());
        assertTrue(new SessionStore(db).getById("s1").getAllUserText().contains("bbbb"));
    }

    @Test
    void sessionRemovedFromIndexIsParsedAgain() throws Exception {
        writeSession("s1", "first");
        scanner(new CountingParser()).scan(false);

        try (Statement st = db.getConnection().createStatement()) {
            st.execute("DELETE FROM sessions WHERE session_id = 's1'");
        }

        CountingParser parser = new CountingParser();
        SessionScanner.ScanResult result = scanner(parser).scan(false);
        assertEquals(1, result.parsed());
        assertEquals(1, result.indexed());
        assertNotNull(new SessionStore(db).getById("s1"));
    }

    @Test
    void forceStillParsesEverything() throws Exception {
        writeSession("s1", "first");
        writeSession("s2", "second");
        scanner(new CountingParser()).scan(false);

        CountingParser parser = new CountingParser();
        SessionScanner.ScanResult forced = scanner(parser).scan(true);
        assertEquals(2, forced.parsed());
        assertEquals(2, forced.indexed());
    }

    @Test
    void twoScansStartedTogetherDoNotOverlap() throws Exception {
        writeSession("s1", "first");
        writeSession("s2", "second");

        CountingParser parser = new CountingParser();
        CountDownLatch release = new CountDownLatch(1);
        parser.blockFirstCall = release;

        // Separate scanner instances, as the scheduler and ScanHandler use.
        Thread a = Thread.ofVirtual().start(() -> scanner(parser).scan(false));
        // Wait until scan A is inside its first parse.
        long deadline = System.currentTimeMillis() + 5_000;
        while (parser.calls.get() == 0 && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        Thread b = Thread.ofVirtual().start(() -> scanner(parser).scan(false));

        Thread.sleep(300); // give B the chance to (wrongly) start parsing in parallel
        assertEquals(1, parser.calls.get(), "scan B must wait while scan A runs");
        release.countDown();
        a.join(5_000);
        b.join(5_000);

        assertEquals(1, parser.maxInFlight.get(), "two scans must never parse at the same time");
        // A parsed both files; B, running after A, found them unchanged.
        assertEquals(2, parser.calls.get());
    }
}
