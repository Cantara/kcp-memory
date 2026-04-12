package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.peer.NodeRegistry;
import com.cantara.kcp.memory.server.TcpExchange;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * GET /nodes — returns JSON array of connected peer nodes.
 *
 * <p>Response format:
 * <pre>
 * [
 *   {"peerId":"laptop","address":"ssh://user@host","lastSeen":"2026-04-12T17:00:00Z",
 *    "status":"ok","sessionCount":42,"eventCount":1337},
 *   ...
 * ]
 * </pre>
 */
public class NodesHandler extends BaseHandler {

    private final NodeRegistry registry;

    public NodesHandler(NodeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }

        List<NodeRegistry.NodeInfo> nodes = registry.list();

        List<Map<String, Object>> result = nodes.stream()
                .map(n -> Map.<String, Object>of(
                        "peerId", n.peerId(),
                        "address", n.address(),
                        "lastSeen", n.lastSeen().toString(),
                        "status", n.status(),
                        "sessionCount", n.sessionCount(),
                        "eventCount", n.eventCount()
                ))
                .toList();

        sendJson(ex, 200, result);
    }
}
