package com.cantara.kcp.memory.peer;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe registry of connected peer nodes in the ExoCortex mesh.
 *
 * <p>Tracks peer identity, address, health metrics, and last-seen timestamps
 * for the control plane's {@code /nodes} endpoint.
 */
public class NodeRegistry {

    /**
     * Immutable snapshot of a registered peer node's state.
     */
    public record NodeInfo(
            String peerId,
            String address,
            Instant lastSeen,
            String status,
            long sessionCount,
            long eventCount
    ) {}

    private record MutableNode(
            String peerId,
            String address,
            Instant lastSeen,
            String status,
            long sessionCount,
            long eventCount
    ) {
        NodeInfo toInfo() {
            return new NodeInfo(peerId, address, lastSeen, status, sessionCount, eventCount);
        }
    }

    private final ConcurrentMap<String, MutableNode> nodes = new ConcurrentHashMap<>();

    /**
     * Register a peer node. If already registered, updates the address and resets lastSeen.
     */
    public void register(String peerId, String address) {
        nodes.put(peerId, new MutableNode(peerId, address, Instant.now(), "ok", 0, 0));
    }

    /**
     * Update health metrics for a peer.
     */
    public void updateHealth(String peerId, long sessionCount, long eventCount) {
        nodes.computeIfPresent(peerId, (id, old) ->
                new MutableNode(id, old.address, Instant.now(), old.status, sessionCount, eventCount));
    }

    /**
     * Update the lastSeen timestamp for a peer.
     */
    public void markSeen(String peerId) {
        nodes.computeIfPresent(peerId, (id, old) ->
                new MutableNode(id, old.address, Instant.now(), old.status, old.sessionCount, old.eventCount));
    }

    /**
     * Remove a peer from the registry.
     */
    public void remove(String peerId) {
        nodes.remove(peerId);
    }

    /**
     * Return a snapshot of all registered nodes, sorted by peerId alphabetically.
     */
    public List<NodeInfo> list() {
        return nodes.values().stream()
                .map(MutableNode::toInfo)
                .sorted(Comparator.comparing(NodeInfo::peerId))
                .toList();
    }

    /**
     * Check if a peer's lastSeen timestamp is older than the given threshold.
     *
     * @return true if the peer exists and is stale; false if the peer doesn't exist or is fresh
     */
    public boolean isStale(String peerId, Duration threshold) {
        MutableNode node = nodes.get(peerId);
        if (node == null) return false;
        return Duration.between(node.lastSeen, Instant.now()).compareTo(threshold) > 0;
    }
}
