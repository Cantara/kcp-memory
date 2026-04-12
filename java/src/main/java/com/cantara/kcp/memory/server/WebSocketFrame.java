package com.cantara.kcp.memory.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * WebSocket RFC 6455 frame encoder/decoder for text frames.
 *
 * <p>Supports:
 * <ul>
 *   <li>Encoding text frames (server-to-client, unmasked)</li>
 *   <li>Decoding text frames (handles both masked client frames and unmasked server frames)</li>
 *   <li>7-bit and 16-bit payload length (no 64-bit needed for our use case)</li>
 * </ul>
 */
public final class WebSocketFrame {

    private WebSocketFrame() {} // utility class

    /**
     * Encode a UTF-8 string as a WebSocket text frame (FIN, opcode 0x1, no masking).
     *
     * @param text the string to encode
     * @return the complete WebSocket frame bytes
     */
    public static byte[] encode(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 4);

        // First byte: FIN (0x80) + text opcode (0x01)
        frame.write(0x81);

        // Second byte: no mask (0x00) + payload length
        if (payload.length < 126) {
            frame.write(payload.length);
        } else {
            // 16-bit extended length
            frame.write(126);
            frame.write((payload.length >> 8) & 0xFF);
            frame.write(payload.length & 0xFF);
        }

        // Payload (no masking for server-to-client)
        frame.write(payload, 0, payload.length);

        return frame.toByteArray();
    }

    /**
     * Read and decode one WebSocket frame from a stream.
     *
     * <p>Handles masked frames (client-to-server) and unmasked frames.
     * Assumes FIN bit is set (no fragmentation). Only text frames (opcode 0x1) are expected.
     *
     * @param in the input stream to read from
     * @return the decoded text content
     * @throws IOException if the stream is closed or frame is malformed
     */
    public static String decode(InputStream in) throws IOException {
        // First byte: FIN + opcode
        int firstByte = in.read();
        if (firstByte == -1) throw new IOException("Unexpected end of stream reading frame header");

        // Second byte: MASK flag + payload length
        int secondByte = in.read();
        if (secondByte == -1) throw new IOException("Unexpected end of stream reading frame length");

        boolean masked = (secondByte & 0x80) != 0;
        int payloadLength = secondByte & 0x7F;

        if (payloadLength == 126) {
            // 16-bit extended length (big-endian)
            int b1 = in.read();
            int b2 = in.read();
            if (b1 == -1 || b2 == -1) throw new IOException("Unexpected end of stream reading extended length");
            payloadLength = (b1 << 8) | b2;
        }
        // Note: payloadLength == 127 (64-bit) is not supported

        // Read masking key if present
        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            readFully(in, maskKey);
        }

        // Read payload
        byte[] payload = new byte[payloadLength];
        readFully(in, payload);

        // Unmask if needed
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= maskKey[i % 4];
            }
        }

        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int offset = 0;
        while (offset < buf.length) {
            int read = in.read(buf, offset, buf.length - offset);
            if (read == -1) throw new IOException("Unexpected end of stream (needed " + buf.length + " bytes, got " + offset + ")");
            offset += read;
        }
    }
}
