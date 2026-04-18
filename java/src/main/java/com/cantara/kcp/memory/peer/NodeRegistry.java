package com.cantara.kcp.memory.peer;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.Collections;

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
            String displayName,
            String address,
            Instant lastSeen,
            String status,
            long sessionCount,
            long eventCount,
            List<String> capabilities
    ) {}

    private record MutableNode(
            String peerId,
            String displayName,
            String address,
            Instant lastSeen,
            String status,
            long sessionCount,
            long eventCount,
            List<String> capabilities
    ) {
        NodeInfo toInfo() {
            return new NodeInfo(peerId, displayName, address, lastSeen, status, sessionCount, eventCount, capabilities);
        }
    }

    private final ConcurrentMap<String, MutableNode> nodes = new ConcurrentHashMap<>();

    /**
     * Register a peer node with capabilities.
     * If already registered, updates address, displayName, capabilities, and resets lastSeen.
     *
     * @param peerId       unique peer identifier (e.g., hostname)
     * @param address      peer address (e.g., ssh://user@host or "local")
     * @param displayName  friendly name shown in UIs; defaults to peerId if null/blank
     * @param capabilities executor tags, e.g. ["claude"], ["ironclaw", "deepseek/deepseek-v3.2"]
     */
    public void register(String peerId, String address, String displayName, List<String> capabilities) {
        String name = (displayName != null && !displayName.isBlank()) ? displayName : peerId;
        List<String> caps = capabilities != null ? List.copyOf(capabilities) : List.of();
        nodes.put(peerId, new MutableNode(peerId, name, address, Instant.now(), "ok", 0, 0, caps));
    }

    /** Register with a friendly display name; capabilities default to empty. */
    public void register(String peerId, String address, String displayName) {
        register(peerId, address, displayName, List.of());
    }

    /** Register with display name defaulting to peerId; capabilities default to empty. */
    public void register(String peerId, String address) {
        register(peerId, address, peerId, List.of());
    }

    /**
     * Update health metrics for a peer.
     */
    public void updateHealth(String peerId, long sessionCount, long eventCount) {
        nodes.computeIfPresent(peerId, (id, old) ->
                new MutableNode(id, old.displayName, old.address, Instant.now(), old.status, sessionCount, eventCount, old.capabilities));
    }

    /**
     * Update the lastSeen timestamp for a peer.
     */
    public void markSeen(String peerId) {
        nodes.computeIfPresent(peerId, (id, old) ->
                new MutableNode(id, old.displayName, old.address, Instant.now(), old.status, old.sessionCount, old.eventCount, old.capabilities));
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
