package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.PendingTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for PendingDispatchHandler — queue, poll, result, and list endpoints.
 */
class PendingDispatchHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Path tempDb;
    private MemoryDatabase db;
    private PendingTaskStore store;
    private PendingDispatchHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-pending-handler-test-", ".db");
        db = new MemoryDatabase(tempDb);
        store = new PendingTaskStore(db);
        handler = new PendingDispatchHandler(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    @Test
    void enqueueReturnsTaskId() throws Exception {
        String body = requestBody("POST", "/dispatch/queue",
                "{\"peerId\":\"laptop\",\"prompt\":\"run the tests\"}");

        JsonNode json = JSON.readTree(body);
        assertNotNull(json.get("taskId").asText());
        assertFalse(json.get("taskId").asText().isBlank());
        assertEquals("laptop", json.get("peerId").asText());
        assertEquals("queued", json.get("status").asText());
    }

    @Test
    void pollReturnsTask() throws Exception {
        // Enqueue first
        store.enqueue("laptop", "build the project");

        // Poll
        String resp = request("GET", "/pending?peer=laptop", "");
        assertTrue(resp.startsWith("HTTP/1.1 200"), "Expected 200, got: " + firstLine(resp));

        String body = extractBody(resp);
        JsonNode json = JSON.readTree(body);
        assertNotNull(json.get("taskId").asText());
        assertEquals("build the project", json.get("prompt").asText());
        assertEquals("claimed", json.get("status").asText());
    }

    @Test
    void pollReturns204WhenEmpty() throws Exception {
        String resp = request("GET", "/pending?peer=unknown", "");
        assertTrue(resp.startsWith("HTTP/1.1 204"), "Expected 204, got: " + firstLine(resp));
    }

    @Test
    void resultMarksTaskDone() throws Exception {
        String taskId = store.enqueue("laptop", "deploy");
        store.claim("laptop");

        String resp = request("POST", "/pending/result",
                "{\"taskId\":\"" + taskId + "\",\"result\":\"deployed ok\"}");
        assertTrue(resp.startsWith("HTTP/1.1 200"), "Expected 200, got: " + firstLine(resp));

        String body = extractBody(resp);
        JsonNode json = JSON.readTree(body);
        assertTrue(json.get("ok").asBoolean());

        // Verify in store
        var task = store.get(taskId);
        assertTrue(task.isPresent());
        assertEquals("done", task.get().status());
        assertEquals("deployed ok", task.get().result());
    }

    @Test
    void resultMarksTaskError() throws Exception {
        String taskId = store.enqueue("laptop", "test suite");
        store.claim("laptop");

        String resp = request("POST", "/pending/result",
                "{\"taskId\":\"" + taskId + "\",\"error\":\"tests failed\"}");
        assertTrue(resp.startsWith("HTTP/1.1 200"), "Expected 200, got: " + firstLine(resp));

        var task = store.get(taskId);
        assertTrue(task.isPresent());
        assertEquals("error", task.get().status());
        assertEquals("tests failed", task.get().error());
    }

    @Test
    void listShowsAllTasksForPeer() throws Exception {
        store.enqueue("laptop", "task one");
        store.enqueue("laptop", "task two");

        String body = requestBody("GET", "/dispatch/queue?peer=laptop", "");
        JsonNode json = JSON.readTree(body);
        assertTrue(json.isArray());
        assertEquals(2, json.size());
    }

    // ---- Test helpers ----

    private String request(String method, String path, String body) throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            StringBuilder responseCapture = new StringBuilder();

            Thread clientThread = Thread.ofVirtual().start(() -> {
                try (Socket client = new Socket("127.0.0.1", port)) {
                    OutputStream out = client.getOutputStream();
                    StringBuilder req = new StringBuilder();
                    req.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
                    req.append("Host: localhost\r\n");
                    if (body != null && !body.isEmpty()) {
                        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
                        req.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
                        req.append("Content-Type: application/json\r\n");
                        req.append("\r\n");
                        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
                        out.write(bodyBytes);
                    } else {
                        req.append("Content-Length: 0\r\n");
                        req.append("\r\n");
                        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
                    }
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

            synchronized (responseCapture) {
                return responseCapture.toString();
            }
        }
    }

    private String requestBody(String method, String path, String body) throws Exception {
        String fullResponse = request(method, path, body);
        return extractBody(fullResponse);
    }

    private String extractBody(String fullResponse) {
        int bodyStart = fullResponse.indexOf("\r\n\r\n");
        assertTrue(bodyStart >= 0, "Response should contain HTTP headers");
        return fullResponse.substring(bodyStart + 4);
    }

    private String firstLine(String response) {
        return response.lines().findFirst().orElse("");
    }
}
