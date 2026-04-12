package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.EventBroadcaster;
import com.cantara.kcp.memory.server.TcpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * WebSocket upgrade handler for GET /ws.
 *
 * <p>Handles the WebSocket handshake:
 * <ol>
 *   <li>Reads {@code Sec-WebSocket-Key} from request headers</li>
 *   <li>Computes {@code Sec-WebSocket-Accept} per RFC 6455</li>
 *   <li>Sends 101 Switching Protocols response</li>
 *   <li>Registers client with {@link EventBroadcaster}</li>
 *   <li>Blocks reading frames until client disconnects</li>
 *   <li>Unsubscribes on disconnect</li>
 * </ol>
 */
public class WsHandler extends BaseHandler {

    private static final Logger LOG = Logger.getLogger(WsHandler.class.getName());
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final EventBroadcaster broadcaster;

    public WsHandler(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        String wsKey = ex.getRequestHeader("Sec-WebSocket-Key");
        if (wsKey == null || wsKey.isBlank()) {
            sendError(ex, 400, "Missing Sec-WebSocket-Key header");
            return;
        }

        String acceptValue = computeAccept(wsKey);

        // Write 101 response directly to the output stream (not via sendResponse,
        // which sets Connection: close)
        OutputStream out = ex.getOutputStream();
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptValue + "\r\n"
                + "\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // Subscribe this client's output stream to the broadcaster
        broadcaster.subscribe(out);
        LOG.fine("WebSocket client connected, subscribers: " + broadcaster.subscriberCount());

        try {
            // Block reading frames from the raw socket input stream.
            // When the client disconnects, read() returns -1 (EOF) or throws IOException.
            InputStream in = ex.getInputStream();
            keepAlive(in);
        } finally {
            broadcaster.unsubscribe(out);
            LOG.fine("WebSocket client disconnected, subscribers: " + broadcaster.subscriberCount());
        }
    }

    /**
     * Block reading from the WebSocket input stream until the client disconnects.
     * Any incoming frames (close, ping, text) are consumed but not processed.
     * EOF or IOException signals disconnection.
     */
    private void keepAlive(InputStream in) {
        try {
            byte[] buf = new byte[1024];
            while (true) {
                int read = in.read(buf);
                if (read == -1) break; // EOF — client disconnected
            }
        } catch (IOException e) {
            // Connection closed — expected
        }
    }

    /**
     * Compute Sec-WebSocket-Accept per RFC 6455 section 4.2.2:
     * Base64(SHA-1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
     */
    static String computeAccept(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update((key + WS_GUID).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sha1.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 not available", e);
        }
    }
}
