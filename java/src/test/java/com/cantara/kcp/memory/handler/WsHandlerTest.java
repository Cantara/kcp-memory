package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.EventBroadcaster;
import com.cantara.kcp.memory.server.TcpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for WsHandler — WebSocket upgrade for GET /ws.
 */
class WsHandlerTest {

    private EventBroadcaster broadcaster;
    private WsHandler handler;

    @BeforeEach
    void setUp() {
        broadcaster = new EventBroadcaster();
        handler = new WsHandler(broadcaster);
    }

    @Test
    void validWsUpgradeReturns101WithCorrectAccept() throws Exception {
        // RFC 6455 example: key "dGhlIHNhbXBsZSBub25jZQ==" -> accept "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        String wsKey = "dGhlIHNhbXBsZSBub25jZQ==";
        String expectedAccept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            StringBuilder responseCapture = new StringBuilder();

            Thread clientThread = Thread.ofVirtual().start(() -> {
                try (Socket client = new Socket("127.0.0.1", port)) {
                    OutputStream out = client.getOutputStream();
                    String request = "GET /ws HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                            + "Content-Length: 0\r\n"
                            + "\r\n";
                    out.write(request.getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    // Read the 101 response (read line by line until empty line)
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) {
                        synchronized (responseCapture) {
                            responseCapture.append(line).append("\n");
                        }
                    }
                    // Close to trigger handler's IOException and unblock
                } catch (IOException ignored) {}
            });

            Socket serverSide = ss.accept();
            try (TcpExchange ex = new TcpExchange(serverSide)) {
                handler.handle(ex);
            }

            clientThread.join(2000);

            String response;
            synchronized (responseCapture) {
                response = responseCapture.toString();
            }

            assertTrue(response.contains("101 Switching Protocols"), "Should return 101");
            assertTrue(response.contains("Upgrade: websocket"), "Should contain Upgrade header");
            assertTrue(response.contains("Connection: Upgrade"), "Should contain Connection header");
            assertTrue(response.contains("Sec-WebSocket-Accept: " + expectedAccept),
                    "Should contain correct Sec-WebSocket-Accept: got:\n" + response);
        }
    }

    @Test
    void missingWebSocketKeyReturns400() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            StringBuilder responseCapture = new StringBuilder();

            Thread clientThread = Thread.ofVirtual().start(() -> {
                try (Socket client = new Socket("127.0.0.1", port)) {
                    OutputStream out = client.getOutputStream();
                    // Missing Sec-WebSocket-Key header
                    String request = "GET /ws HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Content-Length: 0\r\n"
                            + "\r\n";
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

            String response;
            synchronized (responseCapture) {
                response = responseCapture.toString();
            }

            assertTrue(response.contains("400 Bad Request"),
                    "Should return 400 when Sec-WebSocket-Key is missing");
        }
    }

    @Test
    void clientSubscribesToBroadcasterAfterUpgrade() throws Exception {
        assertEquals(0, broadcaster.subscriberCount());

        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread clientThread = Thread.ofVirtual().start(() -> {
                try (Socket client = new Socket("127.0.0.1", port)) {
                    OutputStream out = client.getOutputStream();
                    String request = "GET /ws HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Upgrade: websocket\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                            + "Content-Length: 0\r\n"
                            + "\r\n";
                    out.write(request.getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    // Keep connection alive briefly, then close to trigger handler exit
                    Thread.sleep(200);
                } catch (Exception ignored) {}
            });

            Socket serverSide = ss.accept();

            // Run handle() in a virtual thread (it blocks reading frames)
            Thread handlerThread = Thread.ofVirtual().start(() -> {
                try (TcpExchange ex = new TcpExchange(serverSide)) {
                    handler.handle(ex);
                } catch (IOException ignored) {}
            });

            // Wait for the handler to subscribe
            Thread.sleep(100);
            assertEquals(1, broadcaster.subscriberCount(),
                    "Client should be subscribed to broadcaster");

            // Wait for client to close, triggering unsubscribe
            clientThread.join(2000);
            handlerThread.join(2000);

            assertEquals(0, broadcaster.subscriberCount(),
                    "Client should be unsubscribed after disconnect");
        }
    }
}
