package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.store.MemoryDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #105: a changed transcript was parsed from the start on every scan, so a large active
 * session cost seconds per tool call. A large file is now re-parsed at most once per
 * interval; the interval grows with its size.
 */
class SessionScannerReparseThrottleTest {

    /** Every file counts as large: 10 s between parses, whatever the size. */
    private static final ReparseThrottle TEN_SECONDS = new ReparseThrottle(0, 1, 10_000);

    @TempDir Path tmp;
    private Path root;
    private MemoryDatabase db;
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final AtomicInteger parses = new AtomicInteger();

    private final SessionParser countingParser = new SessionParser() {
        @Override
        public Optional<ParseResult> parse(Path sessionPath, String slug) {
            parses.incrementAndGet();
            return super.parse(sessionPath, slug);
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("projects"));
        db = new MemoryDatabase(tmp.resolve("memory.db"));
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
    }

    private static String line(String id, String text) {
        return "{\"type\":\"user\",\"sessionId\":\"" + id + "\",\"timestamp\":\"2026-03-01T10:00:00Z\""
                + ",\"cwd\":\"/src/app\",\"message\":{\"content\":\"" + text + "\"}}\n";
    }

    private Path write(String id) throws Exception {
        Path file = Files.createDirectories(root.resolve("-src-app")).resolve(id + ".jsonl");
        Files.writeString(file, line(id, "first"));
        return file;
    }

    private static void append(Path file, String id) throws Exception {
        Files.writeString(file, line(id, "more"), StandardOpenOption.APPEND);
    }

    private SessionScanner.ScanResult scan(ReparseThrottle throttle, boolean force) {
        return new SessionScanner(List.of(root), db, countingParser, throttle, now::get).scan(force);
    }

    @Test
    void aChangedFileParsedTooRecentlyIsDeferredNotParsed() throws Exception {
        Path file = write("s1");
        assertEquals(1, scan(TEN_SECONDS, false).parsed());

        append(file, "s1");
        now.addAndGet(4_000);
        SessionScanner.ScanResult second = scan(TEN_SECONDS, false);

        assertEquals(0, second.parsed(), "a file parsed 4 s ago must not be parsed again yet");
        assertEquals(1, parses.get());
        assertEquals(1, second.deferred());
        assertEquals(1, second.skipped(), "a deferred file counts as skipped");
        assertEquals(6_000, second.retryAfterMs(), "due 10 s after the last parse");
    }

    @Test
    void theDeferredFileIsParsedOnceTheIntervalHasPassed() throws Exception {
        Path file = write("s1");
        scan(TEN_SECONDS, false);
        append(file, "s1");
        now.addAndGet(4_000);
        scan(TEN_SECONDS, false);

        now.addAndGet(6_000);
        SessionScanner.ScanResult due = scan(TEN_SECONDS, false);

        assertEquals(1, due.parsed());
        assertEquals(1, due.indexed());
        assertEquals(0, due.deferred());
        assertEquals(0, due.retryAfterMs());
        assertEquals(2, parses.get());
    }

    @Test
    void aForcedScanIgnoresTheThrottle() throws Exception {
        Path file = write("s1");
        scan(TEN_SECONDS, false);
        append(file, "s1");
        now.addAndGet(1_000);

        SessionScanner.ScanResult forced = scan(TEN_SECONDS, true);

        assertEquals(1, forced.parsed());
        assertEquals(0, forced.deferred());
    }

    @Test
    void aFileBelowTheSizeThresholdIsParsedAtOnce() throws Exception {
        Path file = write("s1");
        ReparseThrottle onlyLargeFiles = new ReparseThrottle(1L << 20, 1, 10_000);
        scan(onlyLargeFiles, false);
        append(file, "s1");
        now.addAndGet(1_000);

        SessionScanner.ScanResult second = scan(onlyLargeFiles, false);

        assertEquals(1, second.parsed());
        assertEquals(0, second.deferred());
    }

    @Test
    void anUnchangedFileIsNotCountedAsDeferred() throws Exception {
        write("s1");
        scan(TEN_SECONDS, false);
        now.addAndGet(1_000);

        SessionScanner.ScanResult second = scan(TEN_SECONDS, false);

        assertEquals(0, second.deferred());
        assertEquals(0, second.retryAfterMs());
        assertEquals(1, second.skipped());
    }

    @Test
    void retryAfterIsTheEarliestDueTimeAcrossDeferredFiles() throws Exception {
        Path a = write("s1");
        scan(TEN_SECONDS, false);
        now.addAndGet(3_000);
        Path b = write("s2");
        scan(TEN_SECONDS, false); // parses s2 only; s1 unchanged
        append(a, "s1");
        append(b, "s2");
        now.addAndGet(2_000);

        SessionScanner.ScanResult result = scan(TEN_SECONDS, false);

        assertEquals(2, result.deferred());
        assertEquals(5_000, result.retryAfterMs(), "s1 was parsed 5 s ago, s2 2 s ago");
    }

    @Test
    void theDefaultIntervalGrowsWithFileSizeAndIsCapped() {
        ReparseThrottle t = ReparseThrottle.DEFAULT;
        long mb = 1L << 20;
        assertEquals(0, t.intervalMs(1 * mb));
        assertEquals(0, t.intervalMs(16 * mb - 1));
        assertEquals(2_000, t.intervalMs(16 * mb));
        assertEquals(12_500, t.intervalMs(100 * mb));
        assertEquals(60_000, t.intervalMs(511 * mb));
        assertEquals(0, ReparseThrottle.NONE.intervalMs(511 * mb));
    }

    @Test
    void aFollowUpScanIsRequestedOnceHoweverOftenItIsAsked() throws Exception {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        try {
            AtomicInteger requests = new AtomicInteger();
            FollowUpScan followUp = new FollowUpScan(scheduler, requests::incrementAndGet);

            followUp.after(50);
            followUp.after(50);
            followUp.after(50);
            assertEquals(1, scheduler.getQueue().size(), "only one follow-up may be pending");

            scheduler.shutdown();
            assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, requests.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void aFollowUpCanBeRequestedAgainAfterTheFirstOneRan() throws Exception {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        try {
            AtomicInteger requests = new AtomicInteger();
            FollowUpScan followUp = new FollowUpScan(scheduler, requests::incrementAndGet);

            followUp.after(1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (requests.get() < 1 && System.nanoTime() < deadline) Thread.onSpinWait();
            followUp.after(1);

            scheduler.shutdown();
            assertTrue(scheduler.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, requests.get());
        } finally {
            scheduler.shutdownNow();
        }
    }
}
