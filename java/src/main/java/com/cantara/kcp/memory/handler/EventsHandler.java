package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.model.ToolEvent;
import com.cantara.kcp.memory.scanner.EventLogScanner;
import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.EventStore;
import com.cantara.kcp.memory.store.MemoryDatabase;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * GET /events/search?q=...&limit=20
 *
 * <p>Full-text search over tool-call events ingested from ~/.kcp/events.jsonl.
 * Triggers an incremental EventLogScanner pass before searching so that
 * the most recently logged events are always visible.
 */
public class EventsHandler extends BaseHandler {

    private final MemoryDatabase db;

    public EventsHandler(MemoryDatabase db) {
        this.db = db;
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }

        String query = ex.getRequestURI().getQuery();
        String q     = param(query, "q");
        String since = param(query, "since");
        int    limit = intParam(query, "limit", 20);

        // Run a quick incremental scan so freshly-written events are visible
        new EventLogScanner(db).scan();

        try {
            EventStore store = new EventStore(db);
            List<ToolEvent> results;
            if (q == null || q.isBlank()) {
                // No search query — return recent events by timestamp (used by peer sync)
                results = store.listSince(since, limit);
            } else {
                results = store.search(q, limit);
            }
            sendJson(ex, 200, results);
        } catch (SQLException e) {
            sendError(ex, 500, "Search failed: " + e.getMessage());
        }
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
