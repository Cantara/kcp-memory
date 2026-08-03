package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.MemoryDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #47: POST /ingest/sessions must set a distinguishable provenance for
 * peer-synced sessions, and must not clobber provenance already recorded.
 */
class IngestProvenanceTest {

    private Path tempDb;
    private MemoryDatabase db;
    private IngestHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-test-", ".db");
        db = new MemoryDatabase(tempDb);
        handler = new IngestHandler(db);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    private void postSessions(String payload) throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread clientThread = Thread.ofVirtual().start(() -> {
                try (Socket client = new Socket("127.0.0.1", port)) {
                    OutputStream out = client.getOutputStream();
                    byte[] bodyBytes = payload.getBytes(StandardCharsets.UTF_8);
                    String request = "POST /ingest/sessions HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + bodyBytes.length + "\r\n"
                            + "\r\n";
                    out.write(request.getBytes(StandardCharsets.UTF_8));
                    out.write(bodyBytes);
                    out.flush();
                    client.getInputStream().readAllBytes();
                } catch (IOException ignored) {}
            });

            Socket serverSide = ss.accept();
            try (TcpExchange ex = new TcpExchange(serverSide)) {
                handler.handle(ex);
            }
            clientThread.join(2000);
        }
    }

    private String provenanceOf(String sessionId) throws Exception {
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "SELECT provenance FROM sessions WHERE session_id = ?")) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("provenance") : null;
            }
        }
    }

    @Test
    void peerPushSetsDistinguishableProvenance() throws Exception {
        String payload = """
                {
                  "sessions": [
                    {
                      "session_id": "sess-peer-001",
                      "project_dir": "/src/test",
                      "first_message": "hello",
                      "started_at": "2026-04-12T17:00:00Z",
                      "turn_count": 3,
                      "tool_call_count": 5,
                      "source_instance": "laptop"
                    }
                  ]
                }
                """;
        postSessions(payload);

        String provenance = provenanceOf("sess-peer-001");
        assertNotNull(provenance);
        assertTrue(provenance.startsWith("claude-code-peer:"), "expected peer-prefixed provenance, got: " + provenance);
        assertTrue(provenance.contains("laptop"), "expected source_instance in provenance, got: " + provenance);
    }

    @Test
    void repushDoesNotClobberExistingProvenance() throws Exception {
        String firstPush = """
                {
                  "sessions": [
                    {
                      "session_id": "sess-peer-002",
                      "project_dir": "/src/test",
                      "first_message": "hello",
                      "started_at": "2026-04-12T17:00:00Z",
                      "turn_count": 1,
                      "tool_call_count": 1,
                      "source_instance": "laptop"
                    }
                  ]
                }
                """;
        postSessions(firstPush);
        String firstProvenance = provenanceOf("sess-peer-002");
        assertNotNull(firstProvenance);
        assertTrue(firstProvenance.contains("laptop"));

        String secondPush = firstPush.replace("laptop", "desktop");
        postSessions(secondPush);
        String secondProvenance = provenanceOf("sess-peer-002");

        assertEquals(firstProvenance, secondProvenance, "first-write-wins — a re-push must not overwrite existing provenance");
    }
}
