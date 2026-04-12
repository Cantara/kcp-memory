package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.peer.NodeRegistry;
import com.cantara.kcp.memory.server.TcpExchange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for NodesHandler — GET /nodes returns JSON array of connected peers.
 */
class NodesHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private NodeRegistry registry;
    private NodesHandler handler;

    @BeforeEach
    void setUp() {
        registry = new NodeRegistry();
        handler = new NodesHandler(registry);
    }

    @Test
    void emptyRegistryReturnsEmptyArray() throws Exception {
        String responseBody = executeGetNodes();

        JsonNode json = JSON.readTree(responseBody);
        assertTrue(json.isArray(), "Response should be a JSON array");
        assertEquals(0, json.size());
    }

    @Test
    void oneRegisteredNodeReturnsOneElementArray() throws Exception {
        registry.register("laptop", "ssh://user@laptop");
        registry.updateHealth("laptop", 42, 1337);

        String responseBody = executeGetNodes();

        JsonNode json = JSON.readTree(responseBody);
        assertTrue(json.isArray());
        assertEquals(1, json.size());

        JsonNode node = json.get(0);
        assertEquals("laptop", node.get("peerId").asText());
        assertEquals("ssh://user@laptop", node.get("address").asText());
        assertEquals("ok", node.get("status").asText());
        assertEquals(42, node.get("sessionCount").asInt());
        assertEquals(1337, node.get("eventCount").asInt());
        assertNotNull(node.get("lastSeen").asText());
    }

    @Test
    void responseIsValidJson() throws Exception {
        registry.register("node-a", "tcp://a:7735");
        registry.register("node-b", "tcp://b:7735");

        String responseBody = executeGetNodes();

        // Parse should succeed without exception
        JsonNode json = JSON.readTree(responseBody);
        assertTrue(json.isArray());
        assertEquals(2, json.size());

        // Sorted by peerId
        assertEquals("node-a", json.get(0).get("peerId").asText());
        assertEquals("node-b", json.get(1).get("peerId").asText());
    }

    /**
     * Helper: create a real TCP exchange via ServerSocket, invoke the handler, capture response.
     */
    private String executeGetNodes() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            StringBuilder responseCapture = new StringBuilder();

            Thread clientThread = Thread.ofVirtual().start(() -> {
                try (Socket client = new Socket("127.0.0.1", port)) {
                    OutputStream out = client.getOutputStream();
                    String request = "GET /nodes HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n";
                    out.write(request.getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    String response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    synchronized (responseCapture) {
                        responseCapture.append(response);
                    }
                } catch (IOException ignored) {}
            });

            Socket serverSide = ss.accept();
            try (TcpExchange ex = new TcpExchange(serverSide)) {
                handler.handle(ex);
            }

            clientThread.join(2000);

            String fullResponse;
            synchronized (responseCapture) {
                fullResponse = responseCapture.toString();
            }
            int bodyStart = fullResponse.indexOf("\r\n\r\n");
            assertTrue(bodyStart >= 0, "Response should contain HTTP headers");
            return fullResponse.substring(bodyStart + 4);
        }
    }
}
