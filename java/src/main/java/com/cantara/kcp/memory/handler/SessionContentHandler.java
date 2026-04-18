package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.model.Session;
import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

/**
 * GET /sessions/{sessionId}/content — read and parse a Claude Code JSONL session file.
 *
 * <p>Returns a structured JSON representation of the conversation with user and
 * assistant messages extracted from the raw JSONL transcript.
 *
 * <p>Response shape:
 * <pre>
 * {
 *   "sessionId": "...",
 *   "slug": "...",
 *   "messageCount": 42,
 *   "messages": [
 *     {"role": "user",      "text": "...", "timestamp": "...", "uuid": "..."},
 *     {"role": "assistant", "text": "...", "timestamp": "...", "uuid": "..."}
 *   ]
 * }
 * </pre>
 *
 * <p>Other sub-paths under /sessions/ (not ending with /content) return 404.
 */
public class SessionContentHandler extends BaseHandler {

    private final SessionStore sessionStore;

    public SessionContentHandler(MemoryDatabase db) {
        this.sessionStore = new SessionStore(db);
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }

        String path = ex.getRequestURI().getPath();
        String[] parts = path.split("/");
        // Expected: ["", "sessions", "{sessionId}", "content"]
        // parts[0] = "" (leading slash), parts[1] = "sessions", parts[2] = sessionId, parts[3] = "content"
        String sessionId = parts.length >= 3 ? parts[2] : null;
        String subPath   = parts.length >= 4 ? parts[3] : null;

        if (sessionId == null || sessionId.isBlank()) {
            sendError(ex, 404, "Session ID missing");
            return;
        }

        if (!"content".equals(subPath)) {
            sendError(ex, 404, "Not found — only /sessions/{id}/content is supported");
            return;
        }

        Session session;
        try {
            session = sessionStore.getById(sessionId);
        } catch (SQLException e) {
            sendError(ex, 500, "Database error: " + e.getMessage());
            return;
        }

        if (session == null) {
            sendError(ex, 404, "Session not found: " + sessionId);
            return;
        }

        if (session.getSlug() == null || session.getSlug().isBlank()) {
            sendError(ex, 404, "Session transcript not available on this node (no slug)");
            return;
        }

        Path home = Path.of(System.getProperty("user.home"));
        Path jsonl = home.resolve(".claude/projects")
                        .resolve(session.getSlug())
                        .resolve(session.getSessionId() + ".jsonl");

        if (!Files.exists(jsonl)) {
            sendError(ex, 404, "Session JSONL file not found: " + jsonl);
            return;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(jsonl);
        } catch (IOException e) {
            sendError(ex, 500, "Failed to read session file: " + e.getMessage());
            return;
        }

        ArrayNode messages = MAPPER.createArrayNode();

        for (String line : lines) {
            if (line == null || line.isBlank()) continue;

            JsonNode entry;
            try {
                entry = MAPPER.readTree(line);
            } catch (Exception e) {
                // Skip unparseable lines
                continue;
            }

            String type = entry.path("type").asText(null);
            if (!"user".equals(type) && !"assistant".equals(type)) continue;

            String uuid      = entry.path("uuid").asText(null);
            String timestamp = entry.path("timestamp").asText(null);
            JsonNode message = entry.path("message");
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
                        String blockType = block.path("type").asText(null);
                        if ("text".equals(blockType)) {
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

            if (text == null || text.isBlank()) continue;

            ObjectNode msg = MAPPER.createObjectNode();
            msg.put("role",      type);
            msg.put("text",      text);
            if (timestamp != null) msg.put("timestamp", timestamp);
            if (uuid      != null) msg.put("uuid",      uuid);
            messages.add(msg);
        }

        ObjectNode response = MAPPER.createObjectNode();
        response.put("sessionId",    session.getSessionId());
        response.put("slug",         session.getSlug());
        response.put("messageCount", messages.size());
        response.set("messages",     messages);

        sendJson(ex, 200, response);
    }
}
