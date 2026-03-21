package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.store.MemoryDatabase;
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

    private final List<Path> roots;
    private final SessionParser parser;
    private final SessionStore sessionStore;
    private final ToolUsageStore toolUsageStore;

    public SessionScanner(MemoryDatabase db) {
        this(List.of(DEFAULT_CLAUDE_PROJECTS, DEFAULT_GEMINI_TMP, DEFAULT_CODEX_SESSIONS), db);
    }

    public SessionScanner(Path root, MemoryDatabase db) {
        this(List.of(root), db);
    }

    public SessionScanner(List<Path> roots, MemoryDatabase db) {
        this.roots = List.copyOf(roots);
        this.parser = new SessionParser();
        this.sessionStore = new SessionStore(db);
        this.toolUsageStore = new ToolUsageStore(db);
    }

    /**
     * Scan all configured roots and return a summary.
     *
     * @param force if true, re-index even files already indexed
     */
    public ScanResult scan(boolean force) {
        List<String> errors = new ArrayList<>();
        int indexed = 0, skipped = 0;

        try {
            List<Path> sessionFiles = collectSessionFiles();
            for (Path file : sessionFiles) {
                try {
                    boolean wasIndexed = indexFile(file, force);
                    if (wasIndexed) indexed++; else skipped++;
                } catch (Exception e) {
                    errors.add(file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            errors.add("Walk error: " + e.getMessage());
        }

        LOG.info(String.format("Scan complete: %d indexed, %d skipped, %d errors",
                indexed, skipped, errors.size()));
        return new ScanResult(indexed, skipped, errors.size(), errors);
    }

    private boolean indexFile(Path file, boolean force) throws SQLException {
        Optional<SessionParser.ParseResult> result = parser.parse(file, deriveSlug(file));
        if (result.isEmpty()) return false;

        SessionParser.ParseResult pr = result.get();
        if (!force && isCurrent(file, pr.session().getSessionId())) {
            return false;
        }

        pr.session().setScannedAt(Instant.now().toString());
        sessionStore.upsert(pr.session());
        toolUsageStore.deleteForSession(pr.session().getSessionId());
        toolUsageStore.insertBatch(pr.toolUsages());
        return true;
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

    public record ScanResult(int indexed, int skipped, int errors, List<String> errorMessages) {
        public boolean hasErrors() { return errors > 0; }
    }
}
