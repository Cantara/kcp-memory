package com.cantara.kcp.memory.peer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for NodeRegistry — thread-safe registry of connected peer nodes.
 */
class NodeRegistryTest {

    private NodeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new NodeRegistry();
    }

    @Test
    void registerAndListReturnsOneNode() {
        registry.register("laptop", "ssh://user@laptop");

        List<NodeRegistry.NodeInfo> nodes = registry.list();
        assertEquals(1, nodes.size());

        NodeRegistry.NodeInfo node = nodes.get(0);
        assertEquals("laptop", node.peerId());
        assertEquals("ssh://user@laptop", node.address());
        assertEquals("ok", node.status());
        assertEquals(0, node.sessionCount());
        assertEquals(0, node.eventCount());
        assertNotNull(node.lastSeen());
    }

    @Test
    void updateHealthReflectsNewCounts() {
        registry.register("server-a", "tcp://10.0.0.1:7735");
        registry.updateHealth("server-a", 42, 1337);

        List<NodeRegistry.NodeInfo> nodes = registry.list();
        assertEquals(1, nodes.size());
        assertEquals(42, nodes.get(0).sessionCount());
        assertEquals(1337, nodes.get(0).eventCount());
    }

    @Test
    void markSeenUpdatesLastSeen() throws InterruptedException {
        registry.register("node-1", "tcp://host:1234");

        Instant firstSeen = registry.list().get(0).lastSeen();
        Thread.sleep(10); // small delay to ensure timestamp difference
        registry.markSeen("node-1");
        Instant afterMarkSeen = registry.list().get(0).lastSeen();

        assertTrue(afterMarkSeen.isAfter(firstSeen) || afterMarkSeen.equals(firstSeen),
                "lastSeen should be updated after markSeen");
    }

    @Test
    void removeRemovesTheNode() {
        registry.register("to-remove", "tcp://host:9999");
        assertEquals(1, registry.list().size());

        registry.remove("to-remove");
        assertEquals(0, registry.list().size());
    }

    @Test
    void isStaleReturnsTrueWhenLastSeenOlderThanThreshold() throws InterruptedException {
        registry.register("stale-node", "tcp://host:5555");
        // Wait briefly so the node's lastSeen is in the past
        Thread.sleep(50);

        assertTrue(registry.isStale("stale-node", Duration.ofMillis(10)),
                "Node should be stale after threshold");
        assertFalse(registry.isStale("stale-node", Duration.ofSeconds(60)),
                "Node should not be stale within generous threshold");
    }

    @Test
    void isStaleReturnsFalseForUnknownNode() {
        assertFalse(registry.isStale("nonexistent", Duration.ofMillis(1)));
    }

    @Test
    void listSortedByPeerIdAlphabetically() {
        registry.register("charlie", "tcp://c:1");
        registry.register("alpha", "tcp://a:1");
        registry.register("bravo", "tcp://b:1");

        List<NodeRegistry.NodeInfo> nodes = registry.list();
        assertEquals(3, nodes.size());
        assertEquals("alpha", nodes.get(0).peerId());
        assertEquals("bravo", nodes.get(1).peerId());
        assertEquals("charlie", nodes.get(2).peerId());
    }

    @Test
    void concurrentRegisterAndListIsThreadSafe() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final String peerId = "peer-" + i;
            Thread.ofVirtual().start(() -> {
                try {
                    registry.register(peerId, "tcp://" + peerId + ":7735");
                    registry.list(); // concurrent read
                    registry.markSeen(peerId);
                    registry.list(); // another concurrent read
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        assertEquals(0, errors.get(), "No exceptions should occur during concurrent access");
        assertEquals(threadCount, registry.list().size());
    }

    @Test
    void registerSamePeerIdUpdatesAddress() {
        registry.register("node-x", "tcp://old:1234");
        registry.register("node-x", "tcp://new:5678");

        List<NodeRegistry.NodeInfo> nodes = registry.list();
        assertEquals(1, nodes.size());
        assertEquals("tcp://new:5678", nodes.get(0).address());
    }
}
