package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.cantara.kcp.memory.store.ToolUsageStore;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Walks {@code ~/.claude/projects/} and indexes any .jsonl session files
 * that have been added or modified since the last scan.
 */
public class SessionScanner {

    private static final Logger LOG = Logger.getLogger(SessionScanner.class.getName());

    private static final Path DEFAULT_CLAUDE_PROJECTS =
            Path.of(System.getProperty("user.home"), ".claude", "projects");

    private final Path claudeProjectsDir;
    private final SessionParser parser;
    private final SessionStore sessionStore;
    private final ToolUsageStore toolUsageStore;

    public SessionScanner(MemoryDatabase db) {
        this(DEFAULT_CLAUDE_PROJECTS, db);
    }

    public SessionScanner(Path claudeProjectsDir, MemoryDatabase db) {
        this.claudeProjectsDir = claudeProjectsDir;
        this.parser            = new SessionParser();
        this.sessionStore      = new SessionStore(db);
        this.toolUsageStore    = new ToolUsageStore(db);
    }

    /**
     * Scan all projects and return a summary.
     *
     * @param force if true, re-index even files already indexed
     */
    public ScanResult scan(boolean force) {
        if (!Files.isDirectory(claudeProjectsDir)) {
            LOG.warning("Claude projects directory not found: " + claudeProjectsDir);
            return new ScanResult(0, 0, 0, List.of());
        }

        List<String> errors = new ArrayList<>();
        int indexed = 0, skipped = 0;

        try {
            List<Path> jsonlFiles = collectJsonlFiles(claudeProjectsDir);
            for (Path file : jsonlFiles) {
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

    /** Index a single .jsonl file. Returns true if it was (re-)indexed. */
    private boolean indexFile(Path file, boolean force) throws SQLException {
        String sessionId = file.getFileName().toString().replace(".jsonl", "");

        if (!force) {
            // Check if already indexed and not modified since
            String scannedAt = sessionStore.getScannedAt(sessionId);
            if (scannedAt != null) {
                try {
                    long fileModMs = Files.getLastModifiedTime(file).toMillis();
                    long indexedMs = java.time.Instant.parse(scannedAt).toEpochMilli();
                    if (fileModMs <= indexedMs) return false;
                } catch (Exception ignored) {}
            }
        }

        // Derive slug from parent directory name
        String slug = file.getParent() != null
                ? file.getParent().getFileName().toString()
                : "";

        Optional<SessionParser.ParseResult> result = parser.parse(file, slug);
        if (result.isEmpty()) return false;

        SessionParser.ParseResult pr = result.get();
        sessionStore.upsert(pr.session());
        toolUsageStore.deleteForSession(pr.session().getSessionId());
        toolUsageStore.insertBatch(pr.toolUsages());
        return true;
    }

    private List<Path> collectJsonlFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".jsonl")) {
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
        return files;
    }

    public record ScanResult(int indexed, int skipped, int errors, List<String> errorMessages) {
        public boolean hasErrors() { return errors > 0; }
    }
}
