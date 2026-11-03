package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.scanner.SingleFlightScanTrigger;
import com.cantara.kcp.memory.server.TcpExchange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** #88: concurrent POST /scan calls must run one scan, not one per request. */
class ScanHandlerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode post(ScanHandler handler, String path) throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            byte[][] responseHolder = new byte[1][];

            Thread clientThread = Thread.ofVirtual().start(() -> {
                try (Socket client = new Socket("127.0.0.1", port)) {
                    OutputStream out = client.getOutputStream();
                    out.write(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n")
                            .getBytes(StandardCharsets.UTF_8));
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
            assertTrue(response.startsWith("HTTP/1.1 202"), response);
            return JSON.readTree(response.substring(response.indexOf("\r\n\r\n") + 4));
        }
    }

    @Test
    void scanRequestsWhileAScanRunsAreCoalesced() throws Exception {
        AtomicInteger scans = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SingleFlightScanTrigger trigger = new SingleFlightScanTrigger(force -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            scans.incrementAndGet();
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        }, r -> Thread.ofVirtual().start(r));
        ScanHandler handler = new ScanHandler(trigger);

        JsonNode first = post(handler, "/scan");
        assertEquals("scanning", first.get("status").asText());
        assertFalse(first.get("coalesced").asBoolean());
        assertTrue(started.await(5, TimeUnit.SECONDS));

        for (int i = 0; i < 5; i++) {
            JsonNode next = post(handler, "/scan");
            assertEquals("scanning", next.get("status").asText());
            assertTrue(next.get("coalesced").asBoolean(), "request " + i + " must be coalesced");
        }
        assertEquals(1, scans.get(), "six /scan calls during one scan must not start more scans");

        release.countDown();
        long deadline = System.currentTimeMillis() + 5_000;
        while (trigger.isRunning() && System.currentTimeMillis() < deadline) Thread.onSpinWait();

        assertEquals(2, scans.get(), "the coalesced requests run as exactly one follow-up scan");
        assertEquals(1, maxInFlight.get());
    }
}
