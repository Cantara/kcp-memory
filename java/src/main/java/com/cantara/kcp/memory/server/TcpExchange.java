package com.cantara.kcp.memory.server;

import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin HTTP/1.1 request+response wrapper over a plain {@link Socket}.
 *
 * <p>Replaces {@code com.sun.net.httpserver.HttpExchange}. Uses blocking TCP I/O so
 * that Java 21's NIO selectors (and the AF_UNIX pipes they require on Windows) are
 * never touched. Safe inside MSIX sandboxes where AF_UNIX is unavailable.
 *
 * <p>Usage: construct from an accepted {@link Socket}, call handler logic, then
 * {@link #close()} (or use try-with-resources).
 */
public class TcpExchange implements Closeable {

    private static final Map<Integer, String> STATUS_TEXT = new HashMap<>();
    static {
        STATUS_TEXT.put(200, "OK");
        STATUS_TEXT.put(201, "Created");
        STATUS_TEXT.put(202, "Accepted");
        STATUS_TEXT.put(204, "No Content");
        STATUS_TEXT.put(400, "Bad Request");
        STATUS_TEXT.put(401, "Unauthorized");
        STATUS_TEXT.put(404, "Not Found");
        STATUS_TEXT.put(405, "Method Not Allowed");
        STATUS_TEXT.put(500, "Internal Server Error");
        STATUS_TEXT.put(502, "Bad Gateway");
        STATUS_TEXT.put(503, "Service Unavailable");
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final String method;
    private final URI uri;
    private final byte[] requestBody;
    private final Map<String, String> requestHeaders = new HashMap<>();

    public TcpExchange(Socket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        this.in = socket.getInputStream();

        InputStream in = this.in;

        // --- Parse request line and headers byte-by-byte (safe with binary body) ---
        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isBlank()) {
            throw new IOException("Empty HTTP request");
        }

        String[] parts = requestLine.split(" ", 3);
        this.method = parts.length > 0 ? parts[0] : "GET";
        this.uri = URI.create(parts.length > 1 ? parts[1] : "/");

        // Parse headers
        int contentLength = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                requestHeaders.put(key, value);
                if ("content-length".equalsIgnoreCase(key)) {
                    try { contentLength = Integer.parseInt(value); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }

        // Read body (exact bytes — no charset conversion)
        if (contentLength > 0) {
            requestBody = new byte[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int read = in.read(requestBody, totalRead, contentLength - totalRead);
                if (read < 0) break;
                totalRead += read;
            }
        } else {
            requestBody = new byte[0];
        }
    }

    public String getRequestMethod() { return method; }

    public URI getRequestURI() { return uri; }

    public InputStream getRequestBody() { return new ByteArrayInputStream(requestBody); }

    /**
     * Get a request header value by name (case-insensitive lookup).
     *
     * @param name header name (e.g. "Authorization")
     * @return the header value, or null if not present
     */
    public String getRequestHeader(String name) {
        // Case-insensitive lookup
        for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Send HTTP response headers for streaming (no Content-Length).
     * After calling this, write directly to {@link #getOutputStream()} and flush.
     *
     * @param statusCode  HTTP status
     * @param contentType value for Content-Type header
     */
    public void sendStreamingHeaders(int statusCode, String contentType) throws IOException {
        String reason = STATUS_TEXT.getOrDefault(statusCode, "Unknown");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Transfer-Encoding: chunked\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Get the raw output stream for streaming responses.
     * Call {@link #sendStreamingHeaders(int, String)} first.
     */
    public OutputStream getOutputStream() {
        return out;
    }

    /**
     * Get the raw input stream from the underlying socket.
     *
     * <p>After the HTTP request line, headers, and body have been parsed in the constructor,
     * this stream is positioned after the request body. For WebSocket upgrades (Content-Length: 0),
     * this is where WebSocket frames will appear.
     */
    public InputStream getInputStream() {
        return in;
    }

    /**
     * Send a complete HTTP response and flush.
     *
     * @param statusCode   HTTP status (200, 400, 404, 405, 500, …)
     * @param contentType  value for Content-Type header
     * @param body         response body bytes (may be empty)
     */
    public void sendResponse(int statusCode, String contentType, byte[] body) throws IOException {
        String reason = STATUS_TEXT.getOrDefault(statusCode, "Unknown");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(statusCode).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }

    // --- helpers ---

    /** Read one CRLF-terminated line from the stream (returns null on EOF). */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n' && prev == '\r') {
                byte[] bytes = buf.toByteArray();
                // strip trailing \r
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.US_ASCII);
            }
            buf.write(b);
            prev = b;
        }
        String s = buf.toString(StandardCharsets.US_ASCII);
        return s.isEmpty() ? null : s;
    }
}
