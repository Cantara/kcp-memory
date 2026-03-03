package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * GET /search?q=&lt;query&gt;[&limit=20] — full-text search across session transcripts.
 */
public class SearchHandler extends BaseHandler {

    private final SessionStore sessionStore;

    public SearchHandler(MemoryDatabase db) {
        this.sessionStore = new SessionStore(db);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }
        Map<String, String> params = queryParams(ex);
        String query = params.get("q");
        if (query == null || query.isBlank()) {
            sendError(ex, 400, "Missing required parameter: q");
            return;
        }
        int limit = 20;
        try { limit = Integer.parseInt(params.getOrDefault("limit", "20")); }
        catch (NumberFormatException ignored) {}

        try {
            List<SearchResult> results = sessionStore.search(query, limit);
            sendJson(ex, 200, Map.of("query", query, "count", results.size(), "results", results));
        } catch (Exception e) {
            sendError(ex, 500, "Search failed: " + e.getMessage());
        }
    }
}
