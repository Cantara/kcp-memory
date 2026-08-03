package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.model.Session;
import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** #32: /health must report uptime and scan freshness, not just session count. */
class HealthHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Path tempDb;
    private MemoryDatabase db;

    @BeforeEach
    void setUp() throws Exception {
        tempDb = Files.createTempFile("kcp-test-", ".db");
        db = new MemoryDatabase(tempDb);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        Files.deleteIfExists(tempDb);
    }

    private JsonNode getHealth(HealthHandler handler) throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            byte[][] responseHolder = new byte[1][];

            Thread clientThread = Thread.ofVirtual().start(() -> {
                try (Socket client = new Socket("127.0.0.1", port)) {
                    OutputStream out = client.getOutputStream();
                    out.write("GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    responseHolder[0] = client.getInputStream().readAllBytes();
                } catch (IOException ignored) {}
            });

            Socket serverSide = ss.accept();
            try (TcpExchange ex = new TcpExchange(serverSide)) {
                handler.handle(ex);
            }
            clientThread.join(2000);

            String response = new String(responseHolder[0], StandardCharsets.UTF_8);
            String body = response.substring(response.indexOf("\r\n\r\n") + 4);
            return JSON.readTree(body);
        }
    }

    @Test
    void reportsUptimeSinceStartTime() throws Exception {
        Instant startTime = Instant.now().minusSeconds(90);
        HealthHandler handler = new HealthHandler(db, startTime);

        JsonNode health = getHealth(handler);
        assertEquals("ok", health.get("status").asText());
        long uptime = health.get("uptimeSeconds").asLong();
        assertTrue(uptime >= 90, "expected uptime >= 90s, got " + uptime);
    }

    @Test
    void reportsNullFreshnessWhenNoSessionsScanned() throws Exception {
        HealthHandler handler = new HealthHandler(db, Instant.now());
        JsonNode health = getHealth(handler);
        assertTrue(health.get("lastScannedAt").isNull());
        assertTrue(health.get("freshnessSeconds").isNull());
    }

    @Test
    void reportsFreshnessFromMostRecentScan() throws Exception {
        SessionStore store = new SessionStore(db);
        Session s = new Session();
        s.setSessionId("sess-health-001");
        s.setProjectDir("/src/test");
        s.setStartedAt(Instant.now().toString());
        s.setScannedAt(Instant.now().minusSeconds(30).toString());
        store.upsert(s);

        HealthHandler handler = new HealthHandler(db, Instant.now());
        JsonNode health = getHealth(handler);

        assertFalse(health.get("lastScannedAt").isNull());
        long freshness = health.get("freshnessSeconds").asLong();
        assertTrue(freshness >= 30 && freshness < 60, "expected freshness ~30s, got " + freshness);
    }
}
