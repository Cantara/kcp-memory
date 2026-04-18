package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.peer.NodeRegistry;
import com.cantara.kcp.memory.server.TcpExchange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

/**
 * POST /nodes/register — accepts self-registration from a local peer syncing to this hub.
 *
 * <p>When a local machine pushes sessions to the hub via PeerSyncService, it also
 * POSTs to this endpoint so the hub's NodeRegistry reflects the peer. This makes
 * the {@code /nodes} endpoint on the hub visible to Android and other external clients.
 *
 * <p>Expected JSON body:
 * <pre>
 * {
 *   "peerId":       "hostname",
 *   "sessionCount": 200,
 *   "address":      "ssh://user@host"
 * }
 * </pre>
 *
 * <p>No auth is checked here — this endpoint is registered on the external server
 * which already requires an API key on every request.
 */
public class NodeRegisterHandler extends BaseHandler {

    private static final Logger LOG = Logger.getLogger(NodeRegisterHandler.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final NodeRegistry nodeRegistry;

    public NodeRegisterHandler(NodeRegistry nodeRegistry) {
        this.nodeRegistry = nodeRegistry;
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }

        JsonNode body;
        try {
            body = JSON.readTree(readBody(ex));
        } catch (Exception e) {
            sendError(ex, 400, "Invalid JSON");
            return;
        }

        String peerId = body.path("peerId").asText(null);
        String address = body.path("address").asText("");
        long sessionCount = body.path("sessionCount").asLong(0);
        String displayName = body.path("displayName").asText(peerId);

        if (peerId == null || peerId.isBlank()) {
            sendError(ex, 400, "Missing required field: peerId");
            return;
        }

        nodeRegistry.register(peerId, address, displayName);
        nodeRegistry.markSeen(peerId);
        nodeRegistry.updateHealth(peerId, sessionCount, 0);

        LOG.fine("Node registered: " + peerId + " @ " + address + " (" + sessionCount + " sessions)");
        sendJson(ex, 200, Map.of("status", "registered", "peerId", peerId));
    }
}
