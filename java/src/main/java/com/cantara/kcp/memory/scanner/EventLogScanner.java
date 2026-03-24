package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.model.ToolEvent;
import com.cantara.kcp.memory.store.EventStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Incrementally ingests new lines from ~/.kcp/events.jsonl into the tool_events table.
 * <p>
 * Tracks the last-read byte offset in the event_log_cursor table so that
 * repeated scans only process new events. This makes scans triggered by the
 * PostToolUse hook near-instant — typically under 1ms when only one new event
 * has been appended since the last scan.
 */
public class EventLogScanner {

    private static final Logger       LOG          = Logger.getLogger(EventLogScanner.class.getName());
    private static final Path         DEFAULT_FILE = Path.of(System.getProperty("user.home") + "/.kcp/events.jsonl");
    private static final ObjectMapper MAPPER       = new ObjectMapper();
    private static final int          OUTPUT_LIMIT = 200;

    private final MemoryDatabase db;
    private final Path           eventsFile;

    public EventLogScanner(MemoryDatabase db) {
        this(db, DEFAULT_FILE);
    }

    /** Package-visible constructor for tests — allows injecting a custom events file path. */
    EventLogScanner(MemoryDatabase db, Path eventsFile) {
        this.db         = db;
        this.eventsFile = eventsFile;
    }

    /**
     * Read new lines from events.jsonl since the last offset and insert them into the DB.
     *
     * @return number of events ingested in this scan
     */
    public int scan() {
        if (!Files.exists(eventsFile)) return 0;

        EventStore store = new EventStore(db);
        int count = 0;

        try {
            long offset   = store.getByteOffset();
            long fileSize = Files.size(eventsFile);
            if (offset >= fileSize) return 0; // nothing new

            try (RandomAccessFile raf = new RandomAccessFile(eventsFile.toFile(), "r")) {
                raf.seek(offset);

                String line;
                long lastGoodOffset = offset;

                while ((line = raf.readLine()) != null) {
                    long posAfterLine = raf.getFilePointer();

                    if (line.isBlank()) {
                        lastGoodOffset = posAfterLine;
                        continue;
                    }

                    try {
                        JsonNode node = MAPPER.readTree(line);

                        String type = node.path("type").asText("");

                        // Output capture line — update existing event with preview
                        if ("output".equals(type)) {
                            String sessionId = node.path("session_id").asText("");
                            String cmd       = node.path("command").asText("");
                            String raw       = node.path("output_preview").asText("");
                            if (!sessionId.isBlank() && !cmd.isBlank() && !raw.isBlank()) {
                                String preview = raw.length() > OUTPUT_LIMIT
                                        ? raw.substring(0, OUTPUT_LIMIT) + "…"
                                        : raw;
                                store.updateOutputPreview(sessionId, cmd, preview);
                            }
                            lastGoodOffset = posAfterLine;
                            continue;
                        }

                        // Normal PreToolUse event line
                        String ts  = node.path("ts").asText(null);
                        String cmd = node.path("command").asText("");
                        if (ts == null || cmd.isBlank()) {
                            lastGoodOffset = posAfterLine;
                            continue;
                        }

                        ToolEvent event = new ToolEvent(
                                0,
                                ts,
                                node.path("session_id").asText(""),
                                node.path("project_dir").asText(""),
                                node.path("tool").asText("Bash"),
                                cmd,
                                node.path("manifest_key").isNull() ? null
                                        : node.path("manifest_key").asText(null),
                                null,  // outputPreview — populated later by output line
                                node.path("manifest_version").isNull() ? null
                                        : node.path("manifest_version").asText(null),
                                null
                        );

                        store.insert(event);
                        count++;
                        lastGoodOffset = posAfterLine;

                    } catch (Exception e) {
                        // Skip malformed lines but still advance past them
                        LOG.fine("Skipping malformed event line: " + e.getMessage());
                        lastGoodOffset = posAfterLine;
                    }
                }

                store.setByteOffset(lastGoodOffset);
            }

        } catch (IOException | SQLException e) {
            LOG.warning("EventLogScanner error: " + e.getMessage());
        }

        if (count > 0) LOG.fine("EventLogScanner: ingested " + count + " event(s)");
        return count;
    }
}
