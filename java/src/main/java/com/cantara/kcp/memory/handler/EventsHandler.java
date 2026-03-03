package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.model.ToolEvent;
import com.cantara.kcp.memory.scanner.EventLogScanner;
import com.cantara.kcp.memory.store.EventStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;

/**
 * GET /events/search?q=...&limit=20
 * <p>
 * Full-text search over tool-call events ingested from ~/.kcp/events.jsonl.
 * Triggers an incremental EventLogScanner pass before searching so that
 * the most recently logged events are always visible.
 */
public class EventsHandler implements HttpHandler {

    private final MemoryDatabase db;
    private final ObjectMapper   mapper = new ObjectMapper();

    public EventsHandler(MemoryDatabase db) {
        this.db = db;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        String q     = param(exchange.getRequestURI().getQuery(), "q");
        int    limit = intParam(exchange.getRequestURI().getQuery(), "limit", 20);

        if (q == null || q.isBlank()) {
            sendJson(exchange, mapper.writeValueAsBytes(List.of()));
            return;
        }

        // Run a quick incremental scan so freshly-written events are visible
        new EventLogScanner(db).scan();

        try {
            List<ToolEvent> results = new EventStore(db).search(q, limit);
            sendJson(exchange, mapper.writeValueAsBytes(results));
        } catch (SQLException e) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        }
    }

    private void sendJson(HttpExchange exchange, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
        exchange.close();
    }

    private String param(String query, String name) {
        if (query == null) return null;
        for (String part : query.split("&")) {
            if (part.startsWith(name + "="))
                return URLDecoder.decode(part.substring(name.length() + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    private int intParam(String query, String name, int def) {
        String v = param(query, name);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }
}
