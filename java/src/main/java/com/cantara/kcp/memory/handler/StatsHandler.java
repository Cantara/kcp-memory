package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.cantara.kcp.memory.store.ToolUsageStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /stats — aggregate statistics across all indexed sessions.
 */
public class StatsHandler extends BaseHandler {

    private final SessionStore sessionStore;
    private final ToolUsageStore toolUsageStore;

    public StatsHandler(MemoryDatabase db) {
        this.sessionStore   = new SessionStore(db);
        this.toolUsageStore = new ToolUsageStore(db);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }
        try {
            SessionStore.Stats stats = sessionStore.stats();
            var topTools = toolUsageStore.topTools(10);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("totalSessions",   stats.totalSessions());
            body.put("totalTurns",      stats.totalTurns());
            body.put("totalToolCalls",  stats.totalToolCalls());
            body.put("oldestSession",   stats.oldest());
            body.put("newestSession",   stats.newest());
            body.put("topTools",        topTools);

            sendJson(ex, 200, body);
        } catch (Exception e) {
            sendError(ex, 500, "Stats failed: " + e.getMessage());
        }
    }
}
