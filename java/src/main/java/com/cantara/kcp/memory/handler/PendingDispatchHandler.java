package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.PendingTaskStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * HTTP handler for the pending task queue endpoints.
 *
 * <ul>
 *   <li>{@code POST /dispatch/queue} — enqueue a task for a peer</li>
 *   <li>{@code GET  /dispatch/queue?peer=X&limit=N} — list tasks for a peer</li>
 *   <li>{@code GET  /pending?peer=X} — peer polls for its next task (claims atomically)</li>
 *   <li>{@code POST /pending/result} — peer pushes result back</li>
 * </ul>
 */
public class PendingDispatchHandler extends BaseHandler {

    private static final Logger LOG = Logger.getLogger(PendingDispatchHandler.class.getName());

    private final PendingTaskStore store;

    public PendingDispatchHandler(PendingTaskStore store) {
        this.store = store;
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        try {
            if (path.endsWith("/queue") && "POST".equalsIgnoreCase(method)) {
                handleEnqueue(ex);
            } else if (path.endsWith("/queue") && "GET".equalsIgnoreCase(method)) {
                handleList(ex);
            } else if (path.endsWith("/pending") && "GET".equalsIgnoreCase(method)) {
                handlePoll(ex);
            } else if (path.endsWith("/result") && "POST".equalsIgnoreCase(method)) {
                handleResult(ex);
            } else {
                sendError(ex, 404, "Not found");
            }
        } catch (SQLException e) {
            LOG.warning("Database error: " + e.getMessage());
            sendError(ex, 500, "Database error: " + e.getMessage());
        }
    }

    /**
     * POST /dispatch/queue — enqueue a task for a peer.
     * Body: {"peerId": "laptop", "prompt": "run the tests"}
     */
    private void handleEnqueue(TcpExchange ex) throws IOException, SQLException {
        JsonNode body = MAPPER.readTree(readBody(ex));

        String peerId = body.path("peerId").asText(null);
        String prompt = body.path("prompt").asText(null);

        if (peerId == null || peerId.isBlank()) {
            sendError(ex, 400, "Missing required field: peerId");
            return;
        }
        if (prompt == null || prompt.isBlank()) {
            sendError(ex, 400, "Missing required field: prompt");
            return;
        }

        String taskId = store.enqueue(peerId, prompt);
        LOG.info("Enqueued task " + taskId + " for peer " + peerId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", taskId);
        response.put("peerId", peerId);
        response.put("status", "queued");
        sendJson(ex, 200, response);
    }

    /**
     * GET /dispatch/queue?peer=X&limit=N — list tasks for a peer.
     */
    private void handleList(TcpExchange ex) throws IOException, SQLException {
        Map<String, String> params = queryParams(ex);
        String peerId = params.get("peer");
        if (peerId == null || peerId.isBlank()) {
            sendError(ex, 400, "Missing required query parameter: peer");
            return;
        }

        int limit = 20;
        String limitStr = params.get("limit");
        if (limitStr != null) {
            try { limit = Integer.parseInt(limitStr); }
            catch (NumberFormatException ignored) {}
        }

        List<PendingTaskStore.PendingTask> tasks = store.list(peerId, limit);

        // Map to JSON-friendly format
        List<Map<String, Object>> jsonTasks = tasks.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.id());
            m.put("peerId", t.peerId());
            m.put("prompt", t.prompt());
            m.put("status", t.status());
            m.put("createdAt", t.createdAt());
            m.put("claimedAt", t.claimedAt());
            m.put("completedAt", t.completedAt());
            m.put("result", t.result());
            m.put("error", t.error());
            return m;
        }).toList();

        sendJson(ex, 200, jsonTasks);
    }

    /**
     * GET /pending?peer=X — peer polls for its next task.
     * Claims atomically. Returns 204 if nothing pending.
     */
    private void handlePoll(TcpExchange ex) throws IOException, SQLException {
        Map<String, String> params = queryParams(ex);
        String peerId = params.get("peer");
        if (peerId == null || peerId.isBlank()) {
            sendError(ex, 400, "Missing required query parameter: peer");
            return;
        }

        Optional<PendingTaskStore.PendingTask> task = store.claim(peerId);
        if (task.isEmpty()) {
            ex.sendResponse(204, "application/json", new byte[0]);
            return;
        }

        PendingTaskStore.PendingTask t = task.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("taskId", t.id());
        response.put("prompt", t.prompt());
        response.put("status", t.status());
        sendJson(ex, 200, response);
    }

    /**
     * POST /pending/result — peer pushes result back.
     * Body: {"taskId": "uuid", "result": "...", "error": null}
     */
    private void handleResult(TcpExchange ex) throws IOException, SQLException {
        JsonNode body = MAPPER.readTree(readBody(ex));

        String taskId = body.path("taskId").asText(null);
        if (taskId == null || taskId.isBlank()) {
            sendError(ex, 400, "Missing required field: taskId");
            return;
        }

        String error = body.has("error") && !body.get("error").isNull()
                ? body.get("error").asText() : null;
        String result = body.has("result") && !body.get("result").isNull()
                ? body.get("result").asText() : null;

        if (error != null) {
            store.fail(taskId, error);
        } else {
            store.complete(taskId, result != null ? result : "");
        }

        sendJson(ex, 200, Map.of("ok", true));
    }
}
