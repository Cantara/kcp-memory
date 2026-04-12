package com.cantara.kcp.memory.server;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for WebSocketFrame RFC 6455 text frame encoder/decoder.
 */
class WebSocketFrameTest {

    @Test
    void roundTripShortMessage() throws IOException {
        String message = "Hello, WebSocket!";
        byte[] encoded = WebSocketFrame.encode(message);
        InputStream in = new ByteArrayInputStream(encoded);
        String decoded = WebSocketFrame.decode(in);
        assertEquals(message, decoded);
    }

    @Test
    void roundTripEmptyMessage() throws IOException {
        String message = "";
        byte[] encoded = WebSocketFrame.encode(message);
        InputStream in = new ByteArrayInputStream(encoded);
        String decoded = WebSocketFrame.decode(in);
        assertEquals(message, decoded);
    }

    @Test
    void roundTripMediumMessage() throws IOException {
        // 300 bytes — triggers 16-bit extended length (126-65535 range)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append((char) ('A' + (i % 26)));
        }
        String message = sb.toString();
        assertEquals(300, message.getBytes(StandardCharsets.UTF_8).length);

        byte[] encoded = WebSocketFrame.encode(message);
        InputStream in = new ByteArrayInputStream(encoded);
        String decoded = WebSocketFrame.decode(in);
        assertEquals(message, decoded);
    }

    @Test
    void roundTripExactly126Bytes() throws IOException {
        // Boundary: exactly 126 bytes triggers 16-bit length
        String message = "A".repeat(126);
        byte[] encoded = WebSocketFrame.encode(message);
        InputStream in = new ByteArrayInputStream(encoded);
        String decoded = WebSocketFrame.decode(in);
        assertEquals(message, decoded);
    }

    @Test
    void roundTripExactly125Bytes() throws IOException {
        // Boundary: 125 bytes uses 7-bit length
        String message = "B".repeat(125);
        byte[] encoded = WebSocketFrame.encode(message);
        InputStream in = new ByteArrayInputStream(encoded);
        String decoded = WebSocketFrame.decode(in);
        assertEquals(message, decoded);
    }

    @Test
    void maskedClientFrameDecode() throws IOException {
        // Build a masked text frame as a client would send (per RFC 6455)
        String payload = "Hello";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81); // FIN + text opcode
        frame.write(0x80 | payloadBytes.length); // MASK bit set + 7-bit length

        // Masking key (4 bytes)
        byte[] maskKey = {0x37, 0x13, (byte) 0xAE, 0x4D};
        frame.write(maskKey);

        // Masked payload
        for (int i = 0; i < payloadBytes.length; i++) {
            frame.write(payloadBytes[i] ^ maskKey[i % 4]);
        }

        InputStream in = new ByteArrayInputStream(frame.toByteArray());
        String decoded = WebSocketFrame.decode(in);
        assertEquals(payload, decoded);
    }

    @Test
    void maskedClientFrameWith16BitLength() throws IOException {
        // Masked frame with 16-bit extended length
        String payload = "X".repeat(200);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81); // FIN + text opcode
        frame.write(0x80 | 126); // MASK bit set + 126 signals 16-bit length

        // 16-bit length (big-endian)
        frame.write((payloadBytes.length >> 8) & 0xFF);
        frame.write(payloadBytes.length & 0xFF);

        // Masking key
        byte[] maskKey = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        frame.write(maskKey);

        // Masked payload
        for (int i = 0; i < payloadBytes.length; i++) {
            frame.write(payloadBytes[i] ^ maskKey[i % 4]);
        }

        InputStream in = new ByteArrayInputStream(frame.toByteArray());
        String decoded = WebSocketFrame.decode(in);
        assertEquals(payload, decoded);
    }

    @Test
    void utf8ContentPreserved() throws IOException {
        // Test with multi-byte UTF-8 characters
        String message = "Hello \u00e6\u00f8\u00e5 \u2603 \uD83D\uDE00";
        byte[] encoded = WebSocketFrame.encode(message);
        InputStream in = new ByteArrayInputStream(encoded);
        String decoded = WebSocketFrame.decode(in);
        assertEquals(message, decoded);
    }

    @Test
    void serverFrameIsNotMasked() {
        // Server-to-client frames must NOT have mask bit set
        String message = "test";
        byte[] encoded = WebSocketFrame.encode(message);
        // Second byte: mask bit is the high bit
        int secondByte = encoded[1] & 0xFF;
        assertEquals(0, secondByte & 0x80, "Server frames must not be masked");
    }

    @Test
    void encodeHasCorrectOpcode() {
        String message = "test";
        byte[] encoded = WebSocketFrame.encode(message);
        // First byte: FIN (0x80) + text opcode (0x01) = 0x81
        assertEquals((byte) 0x81, encoded[0]);
    }
}
