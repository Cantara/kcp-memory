package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.ScanFileStateStore;
import com.cantara.kcp.memory.store.SessionStore;
import com.cantara.kcp.memory.store.ToolUsageStore;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Walks known session roots and indexes supported transcript files that have
 * been added or modified since the last scan.
 */
public class SessionScanner {

    private static final Logger LOG = Logger.getLogger(SessionScanner.class.getName());

    private static final Path DEFAULT_CLAUDE_PROJECTS =
            Path.of(System.getProperty("user.home"), ".claude", "projects");
    private static final Path DEFAULT_GEMINI_TMP =
            Path.of(System.getProperty("user.home"), ".gemini", "tmp");
    private static final Path DEFAULT_CODEX_SESSIONS =
            Path.of(System.getProperty("user.home"), ".codex", "sessions");
    private static final Path DEFAULT_PI_SESSIONS =
            Path.of(System.getProperty("user.home"), ".pi", "agent", "sessions");

    private final List<Path> roots;
    private final SessionParser parser;
    private final SessionStore sessionStore;
    private final ToolUsageStore toolUsageStore;
    private final ScanFileStateStore fileState;

    /** One session scan at a time in this JVM, whichever instance or thread starts it (#88). */
    private static final ReentrantLock SCAN_LOCK = new ReentrantLock();

    public SessionScanner(MemoryDatabase db) {
        this(List.of(DEFAULT_CLAUDE_PROJECTS, DEFAULT_GEMINI_TMP, DEFAULT_CODEX_SESSIONS, DEFAULT_PI_SESSIONS), db);
    }

    public SessionScanner(Path root, MemoryDatabase db) {
        this(List.of(root), db);
    }

    public SessionScanner(List<Path> roots, MemoryDatabase db) {
        this(roots, db, new SessionParser());
    }

    /** Test seam: lets a test observe how many files a scan actually parses. */
    SessionScanner(List<Path> roots, MemoryDatabase db, SessionParser parser) {
        this.roots = List.copyOf(roots);
        this.parser = parser;
        this.sessionStore = new SessionStore(db);
        this.toolUsageStore = new ToolUsageStore(db);
        this.fileState = new ScanFileStateStore(db);
    }

    /**
     * Scan all configured roots and return a summary.
     * <p>
     * Scans are serialised JVM-wide (#88): a scan that starts while another one runs
     * (the scheduler and a {@code /scan} request, say) waits for it instead of walking
     * and writing the same files in parallel.
     *
     * @param force if true, re-index even files already indexed
     */
    public ScanResult scan(boolean force) {
        SCAN_LOCK.lock();
        try {
            return scanLocked(force);
        } finally {
            SCAN_LOCK.unlock();
        }
    }

    private ScanResult scanLocked(boolean force) {
        long startNanos = System.nanoTime();
        List<String> errors = new ArrayList<>();
        int[] counts = new int[3]; // indexed, skipped, parsed
        long[] bytesParsed = new long[1];

        try {
            List<Path> sessionFiles = collectSessionFiles();
            for (Path file : sessionFiles) {
                try {
                    boolean wasIndexed = indexFile(file, force, counts, bytesParsed);
                    if (wasIndexed) counts[0]++; else counts[1]++;
                } catch (Exception e) {
                    errors.add(file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            errors.add("Walk error: " + e.getMessage());
        }

        long millis = (System.nanoTime() - startNanos) / 1_000_000;
        // Keep the original "Scan complete: N indexed, N skipped, N errors" prefix: people grep for it.
        LOG.info(String.format("Scan complete: %d indexed, %d skipped, %d errors; %d files parsed (%d bytes), %d ms",
                counts[0], counts[1], errors.size(), counts[2], bytesParsed[0], millis));
        return new ScanResult(counts[0], counts[1], errors.size(), errors, counts[2]);
    }

    private boolean indexFile(Path file, boolean force, int[] counts, long[] bytesParsed)
            throws IOException, SQLException {
        // #88: decide "unchanged" from size/mtime BEFORE reading the file. The stamp is
        // taken before the parse, so a file that grows during the parse is seen as
        // changed on the next scan.
        ScanFileStateStore.FileStamp stamp = ScanFileStateStore.FileStamp.of(file);
        ScanFileStateStore.Entry previous = fileState.get(ScanFileStateStore.SCANNER_SESSION, file);
        if (!force && isUnchangedSinceLastParse(previous, stamp)) {
            return false;
        }

        counts[2]++;
        bytesParsed[0] += stamp.sizeBytes();
        Optional<SessionParser.ParseResult> result = parser.parse(file, deriveSlug(file));
        if (result.isEmpty()) {
            fileState.record(ScanFileStateStore.SCANNER_SESSION, file, "", stamp);
            return false;
        }

        SessionParser.ParseResult pr = result.get();
        String sessionId = pr.session().getSessionId();
        // The mtime <= scanned_at check is only a fallback for files with no recorded
        // state yet (the first scan after upgrading). With state, a differing stamp
        // already says the file changed, even when its mtime is older than scanned_at:
        // a file appended while it was being parsed gets an mtime just before the
        // scanned_at that the parse then writes, and must still be re-indexed.
        if (!force && previous == null && isCurrent(file, sessionId)) {
            fileState.record(ScanFileStateStore.SCANNER_SESSION, file, sessionId, stamp);
            return false;
        }

        pr.session().setScannedAt(Instant.now().toString());
        sessionStore.upsert(pr.session());
        toolUsageStore.deleteForSession(sessionId);
        toolUsageStore.insertBatch(pr.toolUsages());
        fileState.record(ScanFileStateStore.SCANNER_SESSION, file, sessionId, stamp);
        return true;
    }

    private boolean isUnchangedSinceLastParse(ScanFileStateStore.Entry e, ScanFileStateStore.FileStamp stamp)
            throws SQLException {
        if (e == null || !e.stamp().equals(stamp)) return false;
        // The file produced no session last time: nothing to re-check in the index.
        if (e.entityId().isEmpty()) return true;
        // The session may have been removed from the index since; then parse again.
        return sessionStore.getScannedAt(e.entityId()) != null;
    }

    private boolean isCurrent(Path file, String sessionId) throws SQLException {
        String scannedAt = sessionStore.getScannedAt(sessionId);
        if (scannedAt == null) return false;
        try {
            long fileModMs = Files.getLastModifiedTime(file).toMillis();
            long indexedMs = Instant.parse(scannedAt).toEpochMilli();
            return fileModMs <= indexedMs;
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<Path> collectSessionFiles() throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                LOG.fine("Session root not found: " + root);
                continue;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (isSupportedSessionFile(root, file)) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    LOG.fine("Skipping unreadable file: " + file);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return new ArrayList<>(files);
    }

    private boolean isSupportedSessionFile(Path root, Path file) {
        String name = file.getFileName().toString();
        // Skip subagent files — they are handled by AgentSessionScanner
        if (AgentSessionScanner.isSubagentFile(file)) return false;
        if (root.endsWith("projects")) {
            return name.endsWith(".jsonl");
        }
        if (root.endsWith("tmp")) {
            return name.startsWith("session-")
                    && name.endsWith(".json")
                    && file.toString().contains(FileSystems.getDefault().getSeparator() + "chats" + FileSystems.getDefault().getSeparator());
        }
        // Check before the generic "sessions" match below — .pi/agent/sessions also
        // ends with "sessions", but pi filenames aren't rollout-prefixed like Codex's.
        if (root.endsWith(Path.of("agent", "sessions"))) {
            return name.endsWith(".jsonl");
        }
        if (root.endsWith("sessions")) {
            return name.startsWith("rollout-") && name.endsWith(".jsonl");
        }
        return name.endsWith(".jsonl") || name.endsWith(".json");
    }

    private String deriveSlug(Path file) {
        Path parent = file.getParent();
        if (parent == null) return "";

        String filename = file.getFileName().toString();
        if (filename.startsWith("session-") && parent.getFileName() != null && "chats".equals(parent.getFileName().toString())) {
            Path maybeProject = parent.getParent();
            return maybeProject != null && maybeProject.getFileName() != null
                    ? maybeProject.getFileName().toString()
                    : "";
        }
        return parent.getFileName() != null ? parent.getFileName().toString() : "";
    }

    /**
     * @param parsed how many files were actually opened and parsed; files whose size and
     *               mtime matched the last parse are skipped without being read (#88)
     */
    public record ScanResult(int indexed, int skipped, int errors, List<String> errorMessages, int parsed) {
        /** Pre-#88 shape, kept for source compatibility. */
        public ScanResult(int indexed, int skipped, int errors, List<String> errorMessages) {
            this(indexed, skipped, errors, errorMessages, 0);
        }

        public boolean hasErrors() { return errors > 0; }
    }
}
