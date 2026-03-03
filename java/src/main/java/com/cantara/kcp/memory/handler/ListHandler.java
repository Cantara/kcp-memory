package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * GET /sessions[?project=&lt;dir&gt;][&limit=50] — list recent sessions.
 */
public class ListHandler extends BaseHandler {

    private final SessionStore sessionStore;

    public ListHandler(MemoryDatabase db) {
        this.sessionStore = new SessionStore(db);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }
        Map<String, String> params = queryParams(ex);
        String projectDir = params.get("project");
        int limit = 50;
        try { limit = Integer.parseInt(params.getOrDefault("limit", "50")); }
        catch (NumberFormatException ignored) {}

        try {
            List<SearchResult> sessions = sessionStore.list(projectDir, limit);
            sendJson(ex, 200, Map.of(
                    "project", projectDir != null ? projectDir : "",
                    "count", sessions.size(),
                    "sessions", sessions
            ));
        } catch (Exception e) {
            sendError(ex, 500, "List failed: " + e.getMessage());
        }
    }
}
