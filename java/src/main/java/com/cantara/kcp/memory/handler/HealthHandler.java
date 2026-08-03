package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.mcp.McpServer;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.SessionStore;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /health — liveness check with session count, uptime, and scan freshness (#32).
 */
public class HealthHandler extends BaseHandler {

    private final SessionStore sessionStore;
    private final Instant startTime;

    public HealthHandler(MemoryDatabase db, Instant startTime) {
        this.sessionStore = new SessionStore(db);
        this.startTime = startTime;
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }
        try {
            SessionStore.Stats stats = sessionStore.stats();
            Instant lastScanned = parseFlexibleInstant(stats.lastScannedAt());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("sessions", stats.totalSessions());
            body.put("version", McpServer.SERVER_VERSION);
            body.put("uptimeSeconds", Duration.between(startTime, Instant.now()).getSeconds());
            body.put("lastScannedAt", stats.lastScannedAt());
            body.put("freshnessSeconds", lastScanned != null
                    ? Duration.between(lastScanned, Instant.now()).getSeconds() : null);
            sendJson(ex, 200, body);
        } catch (Exception e) {
            sendJson(ex, 200, Map.of("status", "degraded", "error", e.getMessage()));
        }
    }

    /**
     * scanned_at is written in two formats today — ISO-8601 ({@code Instant.toString()})
     * by the scanners, SQLite {@code datetime('now')} (space-separated, no offset) by the
     * ingest/pull paths. Tolerate both rather than fail the whole health check on one.
     */
    private static Instant parseFlexibleInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e1) {
            try {
                return Instant.parse(raw.replace(' ', 'T') + "Z");
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }
}
