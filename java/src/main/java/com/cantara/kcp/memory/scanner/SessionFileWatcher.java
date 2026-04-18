package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.server.EventBroadcaster;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Watches {@code ~/.claude/projects/} recursively for JSONL file changes and
 * broadcasts new messages to all connected WebSocket clients via {@link EventBroadcaster}.
 *
 * <p>Uses Java's {@link WatchService} to detect file modifications. For each modified
 * {@code .jsonl} file, only new bytes (past the last known offset) are read using
 * {@link RandomAccessFile}. Each new line is parsed as a Claude Code JSONL entry;
 * user and assistant messages are extracted and broadcast as {@code session_message}
 * events.
 */
public class SessionFileWatcher {

    private static final Logger LOG = Logger.getLogger(SessionFileWatcher.class.getName());

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EventBroadcaster broadcaster;
    private final Map<Path, Long> fileOffsets = new ConcurrentHashMap<>();
    private WatchService watcher;

    public SessionFileWatcher(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * Start the file watcher on a virtual thread. Registers {@code ~/.claude/projects/}
     * and all existing subdirectories, then loops on {@link WatchService#take()}.
     * New subdirectories created at runtime are registered automatically.
     */
    public void start() {
        Path root = Path.of(System.getProperty("user.home"), ".claude", "projects");

        try {
            watcher = FileSystems.getDefault().newWatchService();

            if (!Files.isDirectory(root)) {
                LOG.fine("SessionFileWatcher: root not found, skipping: " + root);
                return;
            }

            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    dir.register(watcher,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_CREATE);
                    return FileVisitResult.CONTINUE;
                }
            });

            LOG.info("SessionFileWatcher: watching " + root);
        } catch (IOException e) {
            LOG.warning("SessionFileWatcher: failed to initialise WatchService: " + e.getMessage());
            return;
        }

        Thread.ofVirtual().name("session-file-watcher").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key = watcher.take(); // blocks until an event arrives
                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path dir = (Path) key.watchable();
                        Path changed = dir.resolve((Path) event.context());

                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                            // Register newly-created subdirectories so we watch them too
                            if (Files.isDirectory(changed)) {
                                try {
                                    changed.register(watcher,
                                            StandardWatchEventKinds.ENTRY_MODIFY,
                                            StandardWatchEventKinds.ENTRY_CREATE);
                                    LOG.fine("SessionFileWatcher: registered new dir " + changed);
                                } catch (IOException e) {
                                    LOG.fine("SessionFileWatcher: could not register " + changed + ": " + e.getMessage());
                                }
                            }
                        }

                        if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY
                                && changed.toString().endsWith(".jsonl")) {
                            processNewLines(changed);
                        }
                    }
                    key.reset();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.warning("SessionFileWatcher: watcher loop error: " + e.getMessage());
            }
        });
    }

    /**
     * Stop the watcher by closing the {@link WatchService}.
     */
    public void stop() {
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Read any new lines from {@code file} past the last tracked offset, parse
     * each line, and broadcast valid messages.
     */
    private void processNewLines(Path file) {
        long offset = fileOffsets.getOrDefault(file, 0L);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long fileLen = raf.length();
            if (fileLen <= offset) return; // file truncated or no new data

            raf.seek(offset);
            String line;
            while ((line = readLine(raf)) != null) {
                parseLine(line, file);
            }
            fileOffsets.put(file, raf.getFilePointer());
        } catch (IOException e) {
            LOG.fine("SessionFileWatcher: error reading " + file + ": " + e.getMessage());
        }
    }

    /**
     * Read one line (up to {@code '\n'}) from a {@link RandomAccessFile}.
     *
     * @return the line text (without the newline), or {@code null} if no bytes remain
     */
    private String readLine(RandomAccessFile raf) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = raf.read()) != -1) {
            if (b == '\n') return sb.toString();
            sb.append((char) b);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * Parse a single JSONL line and broadcast a {@code session_message} event if
     * the line represents a user or assistant message with non-blank text.
     *
     * <p>Text extraction mirrors {@link com.cantara.kcp.memory.handler.SessionContentHandler}:
     * <ul>
     *   <li>user — {@code message.content} may be a plain string or an array of blocks
     *       (first {@code type:"text"} block wins)</li>
     *   <li>assistant — {@code message.content} is an array; all {@code type:"text"}
     *       blocks are concatenated</li>
     * </ul>
     */
    private void parseLine(String line, Path file) {
        if (line == null || line.isBlank()) return;

        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (Exception e) {
            return; // skip unparseable lines silently
        }

        String type = node.path("type").asText(null);
        if (!"user".equals(type) && !"assistant".equals(type)) return;

        // sessionId is a top-level field in Claude Code JSONL
        String sessionId = node.path("sessionId").asText(null);
        if (sessionId == null || sessionId.isBlank()) {
            // Fall back: derive from filename (strip .jsonl extension)
            String filename = file.getFileName().toString();
            sessionId = filename.endsWith(".jsonl")
                    ? filename.substring(0, filename.length() - 6)
                    : filename;
        }

        JsonNode message = node.path("message");
        JsonNode content = message.path("content");

        String text = null;

        if ("user".equals(type)) {
            if (content.isTextual()) {
                text = content.asText();
            } else if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText(null))) {
                        text = block.path("text").asText(null);
                        break;
                    }
                }
            }
        } else {
            // assistant — content is always an array of blocks
            if (content.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText(null))) {
                        String blockText = block.path("text").asText(null);
                        if (blockText != null && !blockText.isBlank()) {
                            if (!sb.isEmpty()) sb.append("\n");
                            sb.append(blockText);
                        }
                    }
                    // Skip thinking / tool_use blocks
                }
                text = sb.isEmpty() ? null : sb.toString();
            }
        }

        if (text == null || text.isBlank()) return;

        try {
            ObjectNode event = JSON.createObjectNode();
            event.put("type",      "session_message");
            event.put("sessionId", sessionId);
            event.put("role",      type);
            event.put("text",      text);
            event.put("timestamp", node.path("timestamp").asText(""));
            event.put("uuid",      node.path("uuid").asText(""));
            broadcaster.broadcast(JSON.writeValueAsString(event));
        } catch (Exception e) {
            LOG.fine("SessionFileWatcher: failed to broadcast message: " + e.getMessage());
        }
    }
}
