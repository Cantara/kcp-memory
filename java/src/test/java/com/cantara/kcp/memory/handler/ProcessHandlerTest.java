package com.cantara.kcp.memory.handler;

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
 * TDD tests for ProcessHandler — POST /process (systemctl wrapper).
 *
 * <p>Uses a testable subclass that overrides runSystemctl() to avoid
 * actually executing system commands during tests.
 */
class ProcessHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Captured args from the last runSystemctl call */
    private String capturedAction;
    private String capturedService;
    private String cannedOutput = "Active: active (running)";
    private boolean systemctlAvailable = true;

    private ProcessHandler handler;

    @BeforeEach
    void setUp() {
        capturedAction = null;
        capturedService = null;

        handler = new ProcessHandler() {
            @Override
            String runSystemctl(String action, String service) throws IOException {
                if (!systemctlAvailable) {
                    throw new IOException("systemctl not available");
                }
                capturedAction = action;
                capturedService = service;
                return cannedOutput;
            }

            @Override
            boolean isSystemctlAvailable() {
                return systemctlAvailable;
            }
        };
    }

    @Test
    void invalidActionReturns400() throws Exception {
        String resp = request("POST", "/process", "{\"action\":\"destroy\",\"service\":\"kcp-memory\"}");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + firstLine(resp));
        assertTrue(resp.contains("Invalid action"));
    }

    @Test
    void unsafeServiceNameReturns400() throws Exception {
        String resp = request("POST", "/process", "{\"action\":\"status\",\"service\":\"../etc/passwd\"}");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + firstLine(resp));
        assertTrue(resp.contains("Invalid service name"));
    }

    @Test
    void serviceNameWithSpacesReturns400() throws Exception {
        String resp = request("POST", "/process", "{\"action\":\"status\",\"service\":\"rm -rf /\"}");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + firstLine(resp));
    }

    @Test
    void getMethodReturns405() throws Exception {
        String resp = request("GET", "/process", "");

        assertTrue(resp.startsWith("HTTP/1.1 405"), "Expected 405, got: " + firstLine(resp));
    }

    @Test
    void validRequestCallsSystemctl() throws Exception {
        String resp = request("POST", "/process", "{\"action\":\"status\",\"service\":\"kcp-memory\"}");

        assertTrue(resp.startsWith("HTTP/1.1 200"), "Expected 200, got: " + firstLine(resp));
        assertEquals("status", capturedAction);
        assertEquals("kcp-memory", capturedService);
    }

    @Test
    void systemctlOutputReturnedInJson() throws Exception {
        cannedOutput = "Active: active (running) since Mon 2026-04-13";

        String body = requestBody("POST", "/process", "{\"action\":\"restart\",\"service\":\"synthesis\"}");

        JsonNode json = JSON.readTree(body);
        assertEquals("synthesis", json.get("service").asText());
        assertEquals("restart", json.get("action").asText());
        assertEquals(cannedOutput, json.get("output").asText());
    }

    @Test
    void systemctlNotAvailableReturns503() throws Exception {
        systemctlAvailable = false;

        String resp = request("POST", "/process", "{\"action\":\"status\",\"service\":\"kcp-memory\"}");

        assertTrue(resp.startsWith("HTTP/1.1 503"), "Expected 503, got: " + firstLine(resp));
        assertTrue(resp.contains("systemctl not available"));
    }

    @Test
    void missingActionFieldReturns400() throws Exception {
        String resp = request("POST", "/process", "{\"service\":\"kcp-memory\"}");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + firstLine(resp));
    }

    @Test
    void missingServiceFieldReturns400() throws Exception {
        String resp = request("POST", "/process", "{\"action\":\"status\"}");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + firstLine(resp));
    }

    @Test
    void emptyBodyReturns400() throws Exception {
        String resp = request("POST", "/process", "");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + firstLine(resp));
    }

    @Test
    void serviceWithDotsAndUnderscoresAllowed() throws Exception {
        String resp = request("POST", "/process", "{\"action\":\"status\",\"service\":\"my_app.service\"}");

        assertTrue(resp.startsWith("HTTP/1.1 200"), "Expected 200, got: " + firstLine(resp));
        assertEquals("my_app.service", capturedService);
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
        int bodyStart = fullResponse.indexOf("\r\n\r\n");
        assertTrue(bodyStart >= 0, "Response should contain HTTP headers");
        return fullResponse.substring(bodyStart + 4);
    }

    private String firstLine(String response) {
        return response.lines().findFirst().orElse("");
    }
}
