package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.TcpExchange;
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
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for FileHandler — GET /files (dir listing) + GET /files/content (file read).
 */
class FileHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Path tempDir;
    private FileHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("kcp-file-test-");
        handler = new FileHandler(tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }

    // ---- GET /files — directory listing ----

    @Test
    void listEmptyDirReturnsEmptyEntries() throws Exception {
        String body = requestBody("GET /files?path=" + tempDir + " HTTP/1.1");

        JsonNode json = JSON.readTree(body);
        assertEquals(tempDir.toString(), json.get("path").asText());
        assertTrue(json.get("entries").isArray());
        assertEquals(0, json.get("entries").size());
    }

    @Test
    void listDirReturnsDirsBeforeFiles() throws Exception {
        Files.writeString(tempDir.resolve("alpha.txt"), "hello");
        Files.createDirectory(tempDir.resolve("zulu-dir"));

        String body = requestBody("GET /files?path=" + tempDir + " HTTP/1.1");

        JsonNode entries = JSON.readTree(body).get("entries");
        assertEquals(2, entries.size());
        // Dir first, then file
        assertEquals("dir", entries.get(0).get("type").asText());
        assertEquals("zulu-dir", entries.get(0).get("name").asText());
        assertEquals("file", entries.get(1).get("type").asText());
        assertEquals("alpha.txt", entries.get(1).get("name").asText());
    }

    @Test
    void listDirSortsAlphabetically() throws Exception {
        Files.createDirectory(tempDir.resolve("beta-dir"));
        Files.createDirectory(tempDir.resolve("alpha-dir"));
        Files.writeString(tempDir.resolve("charlie.txt"), "c");
        Files.writeString(tempDir.resolve("bravo.txt"), "b");

        String body = requestBody("GET /files?path=" + tempDir + " HTTP/1.1");

        JsonNode entries = JSON.readTree(body).get("entries");
        assertEquals(4, entries.size());
        // Dirs first, alphabetical
        assertEquals("alpha-dir", entries.get(0).get("name").asText());
        assertEquals("beta-dir", entries.get(1).get("name").asText());
        // Files next, alphabetical
        assertEquals("bravo.txt", entries.get(2).get("name").asText());
        assertEquals("charlie.txt", entries.get(3).get("name").asText());
    }

    @Test
    void listDirIncludesFileSize() throws Exception {
        Files.writeString(tempDir.resolve("sized.txt"), "12345");

        String body = requestBody("GET /files?path=" + tempDir + " HTTP/1.1");

        JsonNode entry = JSON.readTree(body).get("entries").get(0);
        assertEquals("sized.txt", entry.get("name").asText());
        assertTrue(entry.get("size").asLong() > 0);
    }

    @Test
    void listDirIncludesModifiedTimestamp() throws Exception {
        Files.writeString(tempDir.resolve("ts.txt"), "time");

        String body = requestBody("GET /files?path=" + tempDir + " HTTP/1.1");

        JsonNode entry = JSON.readTree(body).get("entries").get(0);
        assertNotNull(entry.get("modified"));
        // ISO-8601 format contains "T"
        assertTrue(entry.get("modified").asText().contains("T"));
    }

    @Test
    void listNonExistentPathReturns404() throws Exception {
        String resp = request("GET /files?path=" + tempDir + "/no-such-dir HTTP/1.1");

        assertTrue(resp.startsWith("HTTP/1.1 404"), "Expected 404, got: " + resp.lines().findFirst().orElse(""));
    }

    @Test
    void listFilePathReturns400() throws Exception {
        Files.writeString(tempDir.resolve("afile.txt"), "data");

        String resp = request("GET /files?path=" + tempDir + "/afile.txt HTTP/1.1");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + resp.lines().findFirst().orElse(""));
    }

    @Test
    void pathTraversalBlocked() throws Exception {
        String resp = request("GET /files?path=" + tempDir + "/../../etc/passwd HTTP/1.1");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + resp.lines().findFirst().orElse(""));
    }

    @Test
    void defaultPathWhenNoPathParam() throws Exception {
        // When no path param is given, should list the allowed root
        String body = requestBody("GET /files HTTP/1.1");

        JsonNode json = JSON.readTree(body);
        assertEquals(tempDir.toString(), json.get("path").asText());
    }

    // ---- GET /files/content — file reading ----

    @Test
    void readFileReturnsContent() throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "Hello, world!");

        String resp = request("GET /files/content?path=" + tempDir + "/hello.txt HTTP/1.1");

        assertTrue(resp.startsWith("HTTP/1.1 200"), "Expected 200, got: " + resp.lines().findFirst().orElse(""));
        assertTrue(resp.contains("Hello, world!"));
    }

    @Test
    void readFileTooLargeReturns400() throws Exception {
        // Create a file > 1MB
        byte[] big = new byte[1024 * 1024 + 1];
        Files.write(tempDir.resolve("big.bin"), big);

        String resp = request("GET /files/content?path=" + tempDir + "/big.bin HTTP/1.1");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + resp.lines().findFirst().orElse(""));
    }

    @Test
    void readDirAsContentReturns400() throws Exception {
        Files.createDirectory(tempDir.resolve("subdir"));

        String resp = request("GET /files/content?path=" + tempDir + "/subdir HTTP/1.1");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + resp.lines().findFirst().orElse(""));
    }

    @Test
    void readNonExistentFileReturns404() throws Exception {
        String resp = request("GET /files/content?path=" + tempDir + "/ghost.txt HTTP/1.1");

        assertTrue(resp.startsWith("HTTP/1.1 404"), "Expected 404, got: " + resp.lines().findFirst().orElse(""));
    }

    @Test
    void readContentPathTraversalBlocked() throws Exception {
        String resp = request("GET /files/content?path=" + tempDir + "/../../etc/passwd HTTP/1.1");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + resp.lines().findFirst().orElse(""));
    }

    @Test
    void readBinaryFileReturns400() throws Exception {
        // Write bytes that are not valid UTF-8
        byte[] binary = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}; // PNG header
        Files.write(tempDir.resolve("image.png"), binary);

        String resp = request("GET /files/content?path=" + tempDir + "/image.png HTTP/1.1");

        assertTrue(resp.startsWith("HTTP/1.1 400"), "Expected 400, got: " + resp.lines().findFirst().orElse(""));
        assertTrue(resp.contains("Binary file"));
    }

    @Test
    void defaultPathIsUserHome() {
        FileHandler defaultHandler = new FileHandler();
        // The constructor should set allowedRoot to user.home
        // We can only test that it doesn't throw
        assertNotNull(defaultHandler);
    }

    // ---- Test helpers ----

    /**
     * Send an HTTP request through a real TcpExchange and return the full response string.
     */
    private String request(String requestLine) throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            StringBuilder responseCapture = new StringBuilder();

            Thread clientThread = Thread.ofVirtual().start(() -> {
                try (Socket client = new Socket("127.0.0.1", port)) {
                    OutputStream out = client.getOutputStream();
                    String httpRequest = requestLine + "\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n";
                    out.write(httpRequest.getBytes(StandardCharsets.UTF_8));
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

    /**
     * Send request and return only the body (after HTTP headers).
     */
    private String requestBody(String requestLine) throws Exception {
        String fullResponse = request(requestLine);
        int bodyStart = fullResponse.indexOf("\r\n\r\n");
        assertTrue(bodyStart >= 0, "Response should contain HTTP headers");
        return fullResponse.substring(bodyStart + 4);
    }
}
