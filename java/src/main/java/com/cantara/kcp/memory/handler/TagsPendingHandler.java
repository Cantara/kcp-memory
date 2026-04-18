package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.PendingTagsStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles the /tags/pending endpoint used by the UserPromptSubmit hook to
 * enqueue session tags before the session is indexed by the scanner.
 *
 * POST /tags/pending
 *   Body: {"session_id": "<uuid>", "tags": ["tag1", "tag2"]}
 *   Response 200: {} (success)
 *   Response 400: missing fields
 *
 * GET /tags/pending?session_id=<uuid>
 *   Response 200: {"session_id": "...", "tags": [...]}  (idempotency check — already queued)
 *   Response 404: not yet queued
 */
public class TagsPendingHandler extends BaseHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PendingTagsStore pendingTagsStore;

    public TagsPendingHandler(MemoryDatabase db) {
        this.pendingTagsStore = new PendingTagsStore(db);
    }

    @Override
    public void handle(TcpExchange exchange) throws IOException {
        try {
            switch (exchange.getRequestMethod()) {
                case "POST" -> handlePost(exchange);
                case "GET"  -> handleGet(exchange);
                default     -> sendError(exchange, 405, "Method Not Allowed");
            }
        } catch (SQLException e) {
            sendError(exchange, 500, "DB error: " + e.getMessage());
        }
    }

    private void handlePost(TcpExchange exchange) throws IOException, SQLException {
        String body = readBody(exchange);
        JsonNode node;
        try {
            node = MAPPER.readTree(body);
        } catch (Exception e) {
            sendError(exchange, 400, "Invalid JSON");
            return;
        }

        String sessionId = node.path("session_id").asText(null);
        if (sessionId == null || sessionId.isBlank()) {
            sendError(exchange, 400, "session_id required");
            return;
        }

        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = node.path("tags");
        if (tagsNode.isArray()) {
            tagsNode.forEach(t -> {
                if (t.isTextual() && !t.asText().isBlank()) {
                    tags.add(t.asText());
                }
            });
        }
        if (tags.isEmpty()) {
            sendError(exchange, 400, "tags array required and must not be empty");
            return;
        }

        pendingTagsStore.enqueue(sessionId, tags);
        sendJson(exchange, 200, "{}");
    }

    private void handleGet(TcpExchange exchange) throws IOException, SQLException {
        String sessionId = queryParams(exchange).get("session_id");
        if (sessionId == null || sessionId.isBlank()) {
            sendError(exchange, 400, "session_id query param required");
            return;
        }

        List<String> tags = pendingTagsStore.get(sessionId);
        if (tags.isEmpty()) {
            sendError(exchange, 404, "No pending tags for session_id: " + sessionId);
            return;
        }

        String tagsJson = MAPPER.writeValueAsString(tags);
        sendJson(exchange, 200,
                "{\"session_id\":\"" + sessionId + "\",\"tags\":" + tagsJson + "}");
    }
}
