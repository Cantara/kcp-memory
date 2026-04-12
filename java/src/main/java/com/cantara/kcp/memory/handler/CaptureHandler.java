package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.TcpExchange;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.logging.Logger;

/**
 * POST /capture — ingest knowledge captures from mobile.
 *
 * <p>Accepts JSON body with:
 * <pre>
 * {
 *   "type": "note",         // "note", "voice_transcript", "photo_ocr"
 *   "content": "...",       // text content
 *   "tags": ["lib-pcb"],   // optional tags
 *   "title": "..."         // optional title
 * }
 * </pre>
 *
 * <p>Writes a markdown file to the capture directory. Synthesis picks it up
 * on its next indexing pass.
 */
public class CaptureHandler extends BaseHandler {

    private static final Logger LOG = Logger.getLogger(CaptureHandler.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC);

    private final Path captureDir;

    public CaptureHandler(Path captureDir) {
        this.captureDir = captureDir;
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }

        var body = JSON.readTree(ex.getRequestBody());

        String type = body.path("type").asText("note");
        String content = body.path("content").asText(null);
        String title = body.path("title").asText(null);

        if (content == null || content.isBlank()) {
            sendError(ex, 400, "Missing required field: content");
            return;
        }

        // Build tags
        StringBuilder tags = new StringBuilder();
        var tagsNode = body.get("tags");
        if (tagsNode != null && tagsNode.isArray()) {
            for (var tag : tagsNode) {
                if (!tags.isEmpty()) tags.append(", ");
                tags.append(tag.asText());
            }
        }

        // Generate filename: capture-2026-04-12T14-30-00-note.md
        String timestamp = TS_FORMAT.format(Instant.now());
        String filename = "capture-" + timestamp + "-" + type + ".md";
        Path filePath = captureDir.resolve(filename);

        // Write as markdown with frontmatter (Synthesis-friendly)
        StringBuilder md = new StringBuilder();
        md.append("---\n");
        md.append("type: ").append(type).append("\n");
        md.append("captured: ").append(Instant.now().toString()).append("\n");
        md.append("source: mobile\n");
        if (!tags.isEmpty()) md.append("tags: [").append(tags).append("]\n");
        md.append("---\n\n");
        if (title != null) md.append("# ").append(title).append("\n\n");
        md.append(content).append("\n");

        // Ensure directory exists and write
        Files.createDirectories(captureDir);
        Files.writeString(filePath, md.toString());

        LOG.info("Captured " + type + ": " + filename);
        sendJson(ex, 201, Map.of(
                "status", "captured",
                "file", filename,
                "path", filePath.toString()
        ));
    }
}
