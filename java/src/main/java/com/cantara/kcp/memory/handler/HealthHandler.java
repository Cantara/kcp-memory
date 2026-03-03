package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/**
 * GET /health — liveness check with session count.
 */
public class HealthHandler extends BaseHandler {

    private final SessionStore sessionStore;

    public HealthHandler(MemoryDatabase db) {
        this.sessionStore = new SessionStore(db);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }
        try {
            SessionStore.Stats stats = sessionStore.stats();
            sendJson(ex, 200, Map.of(
                    "status", "ok",
                    "sessions", stats.totalSessions(),
                    "version", "0.4.0"
            ));
        } catch (Exception e) {
            sendJson(ex, 200, Map.of("status", "degraded", "error", e.getMessage()));
        }
    }
}
