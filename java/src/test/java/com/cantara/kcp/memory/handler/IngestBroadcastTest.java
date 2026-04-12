package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.EventBroadcaster;
import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.server.WebSocketFrame;
import com.cantara.kcp.memory.store.MemoryDatabase;
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
 * TDD test: when events are ingested via POST /ingest/events,
 * they should be broadcast to the EventBroadcaster.
 */
class IngestBroadcastTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Path tempDb;
    private MemoryDatabase db;
    private EventBroadcaster broadcaster;
    private IngestHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-test-", ".db");
        db = new MemoryDatabase(tempDb);
        broadcaster = new EventBroadcaster();
        handler = new IngestHandler(db);
        handler.setBroadcaster(broadcaster);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    @Test
    void ingestEventBroadcastsToSubscriber() throws Exception {
        // Set up a subscriber via piped streams
        PipedInputStream pipeIn = new PipedInputStream(4096);
        PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
        broadcaster.subscribe(pipeOut);

        // Build event payload
        String payload = """
                {
                  "events": [
                    {
                      "event_ts": "2026-04-12T17:00:00Z",
                      "tool": "Bash",
                      "command": "ls -la",
                      "session_id": "sess-001",
                      "project_dir": "/src/test",
                      "source_instance": "laptop"
                    }
                  ]
                }
                """;

        // Execute POST /ingest/events via real TCP exchange
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread clientThread = Thread.ofVirtual().start(() -> {
                try (Socket client = new Socket("127.0.0.1", port)) {
                    OutputStream out = client.getOutputStream();
                    byte[] bodyBytes = payload.getBytes(StandardCharsets.UTF_8);
                    String request = "POST /ingest/events HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + bodyBytes.length + "\r\n"
                            + "\r\n";
                    out.write(request.getBytes(StandardCharsets.UTF_8));
                    out.write(bodyBytes);
                    out.flush();

                    // Read response
                    client.getInputStream().readAllBytes();
                } catch (IOException ignored) {}
            });

            Socket serverSide = ss.accept();
            try (TcpExchange ex = new TcpExchange(serverSide)) {
                handler.handle(ex);
            }

            clientThread.join(2000);
        }

        // Read the broadcast message from the pipe
        String broadcastedJson = WebSocketFrame.decode(pipeIn);

        JsonNode node = JSON.readTree(broadcastedJson);
        assertEquals("tool_event", node.get("type").asText());
        assertEquals("Bash", node.get("tool").asText());
        assertEquals("ls -la", node.get("command").asText());
        assertNotNull(node.get("timestamp"));
    }
}
