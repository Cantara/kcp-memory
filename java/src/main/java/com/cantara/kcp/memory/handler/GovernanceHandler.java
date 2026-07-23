package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Memory governance endpoints.
 *
 * <ul>
 *   <li>GET  /governance?session=&lt;id&gt;            — governance metadata for a memory
 *       (provenance, retention, forget tombstone)</li>
 *   <li>GET  /governance/audit?q=&lt;query&gt;[&limit] — audit the recall gate: which
 *       matches are surfaced vs. skipped, and why</li>
 *   <li>POST /governance/retention {session, valid_until} — declare/clear a retention window</li>
 *   <li>POST /governance/forget    {session, reason}      — exercise the right-to-forget (tombstone)</li>
 * </ul>
 */
public class GovernanceHandler extends BaseHandler {

    private final SessionStore store;

    public GovernanceHandler(MemoryDatabase db) {
        this.store = new SessionStore(db);
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        String path   = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if ("GET".equalsIgnoreCase(method) && path.endsWith("/audit")) {
                handleAudit(ex);
            } else if ("GET".equalsIgnoreCase(method)) {
                handleGet(ex);
            } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/retention")) {
                handleRetention(ex);
            } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/forget")) {
                handleForget(ex);
            } else {
                sendError(ex, 405, "Method not allowed");
            }
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getMessage());
        } catch (Exception e) {
            sendError(ex, 500, "Governance operation failed: " + e.getMessage());
        }
    }

    private void handleGet(TcpExchange ex) throws Exception {
        String session = queryParams(ex).get("session");
        if (session == null || session.isBlank()) {
            sendError(ex, 400, "Missing required parameter: session");
            return;
        }
        SessionStore.Governance g = store.getGovernance(session);
        if (g == null) {
            sendError(ex, 404, "No memory for session: " + session);
            return;
        }
        sendJson(ex, 200, g);
    }

    private void handleAudit(TcpExchange ex) throws Exception {
        Map<String, String> params = queryParams(ex);
        String query = params.get("q");
        if (query == null || query.isBlank()) {
            sendError(ex, 400, "Missing required parameter: q");
            return;
        }
        int limit = parseLimit(params.getOrDefault("limit", "20"));
        List<SessionStore.RecallAudit> audit = store.auditSearch(query, limit);
        long skipped = audit.stream().filter(a -> !a.allowed()).count();
        sendJson(ex, 200, Map.of(
                "query", query,
                "candidates", audit.size(),
                "skipped", skipped,
                "results", audit));
    }

    private void handleRetention(TcpExchange ex) throws Exception {
        JsonNode body = MAPPER.readTree(readBody(ex));
        String session = body.path("session").asText("");
        if (session.isBlank()) {
            sendError(ex, 400, "Missing required field: session");
            return;
        }
        // null / absent valid_until clears the retention window
        String validUntil = body.has("valid_until") && !body.get("valid_until").isNull()
                ? body.get("valid_until").asText() : null;
        boolean updated = store.setRetention(session, validUntil);
        if (!updated) {
            sendError(ex, 404, "No memory for session: " + session);
            return;
        }
        sendJson(ex, 200, mapNullable("session", session, "valid_until", validUntil));
    }

    private void handleForget(TcpExchange ex) throws Exception {
        JsonNode body = MAPPER.readTree(readBody(ex));
        String session = body.path("session").asText("");
        if (session.isBlank()) {
            sendError(ex, 400, "Missing required field: session");
            return;
        }
        String reason = body.has("reason") && !body.get("reason").isNull()
                ? body.get("reason").asText() : null;
        boolean forgotten = store.forget(session, reason);
        if (!forgotten) {
            sendError(ex, 404, "No memory for session: " + session);
            return;
        }
        sendJson(ex, 200, mapNullable("session", session, "forgotten", "true", "reason", reason));
    }

    private int parseLimit(String raw) {
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return 20; }
    }

    /** Build a small map tolerating null values (Map.of rejects nulls). */
    private static Map<String, Object> mapNullable(Object... kv) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
