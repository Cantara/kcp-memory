package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.TcpExchange;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * GET /files — directory listing (hub-local, dirs-first sorted, path traversal protected).
 * GET /files/content — read file content (max 1MB, UTF-8 only).
 *
 * <p>Security: all paths are validated against {@code allowedRoot}. Path traversal
 * attempts (e.g. {@code ../../etc/passwd}) are rejected with HTTP 400.
 *
 * <h3>Android client reference</h3>
 * <ul>
 *   <li>{@code GET /files?path=...} &rarr; parse JSON to {@code FileListResponse(path, entries)}</li>
 *   <li>{@code GET /files/content?path=...} &rarr; String (raw text)</li>
 * </ul>
 */
public class FileHandler extends BaseHandler {

    private static final long MAX_FILE_SIZE = 1024 * 1024; // 1 MB
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final Path allowedRoot;

    public FileHandler() {
        this(Path.of(System.getProperty("user.home")));
    }

    public FileHandler(Path allowedRoot) {
        this.allowedRoot = allowedRoot.toAbsolutePath().normalize();
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        String uriPath = ex.getRequestURI().getPath();

        if (uriPath.endsWith("/content")) {
            handleContent(ex);
        } else {
            handleList(ex);
        }
    }

    private void handleList(TcpExchange ex) throws IOException {
        Map<String, String> params = queryParams(ex);
        String pathParam = params.get("path");

        Path target;
        try {
            target = resolveSafe(pathParam);
        } catch (SecurityException e) {
            sendError(ex, 400, e.getMessage());
            return;
        }

        if (!Files.exists(target)) {
            sendError(ex, 404, "Path not found: " + target);
            return;
        }

        if (!Files.isDirectory(target)) {
            sendError(ex, 400, "Path is a file, not a directory. Use /files/content to read files.");
            return;
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(target)) {
            List<Path> children = stream.sorted(Comparator.comparing(Path::getFileName)).toList();

            // Separate dirs and files, then merge (dirs first)
            List<Path> dirs = new ArrayList<>();
            List<Path> files = new ArrayList<>();
            for (Path child : children) {
                if (Files.isDirectory(child)) {
                    dirs.add(child);
                } else {
                    files.add(child);
                }
            }

            for (Path dir : dirs) {
                entries.add(entryMap(dir, "dir"));
            }
            for (Path file : files) {
                entries.add(entryMap(file, "file"));
            }
        }

        sendJson(ex, 200, Map.of(
                "path", target.toString(),
                "entries", entries
        ));
    }

    private void handleContent(TcpExchange ex) throws IOException {
        Map<String, String> params = queryParams(ex);
        String pathParam = params.get("path");

        Path target;
        try {
            target = resolveSafe(pathParam);
        } catch (SecurityException e) {
            sendError(ex, 400, e.getMessage());
            return;
        }

        if (!Files.exists(target)) {
            sendError(ex, 404, "File not found: " + target);
            return;
        }

        if (Files.isDirectory(target)) {
            sendError(ex, 400, "Path is a directory. Use /files to list directories.");
            return;
        }

        long size = Files.size(target);
        if (size > MAX_FILE_SIZE) {
            sendError(ex, 400, "File too large (" + size + " bytes). Maximum is " + MAX_FILE_SIZE + " bytes.");
            return;
        }

        // Read raw bytes and check if valid UTF-8
        byte[] raw = Files.readAllBytes(target);
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException e) {
            sendError(ex, 400, "Binary file — not viewable");
            return;
        }

        ex.sendResponse(200, "text/plain; charset=utf-8", content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Resolve path parameter safely within allowedRoot.
     *
     * @param pathParam the path query parameter (may be null for default)
     * @return resolved and validated absolute path
     * @throws SecurityException if path traversal is detected
     */
    private Path resolveSafe(String pathParam) throws SecurityException {
        if (pathParam == null || pathParam.isBlank()) {
            return allowedRoot;
        }

        Path resolved = Path.of(pathParam).toAbsolutePath().normalize();
        if (!resolved.startsWith(allowedRoot)) {
            throw new SecurityException("Access denied — path outside allowed root");
        }
        return resolved;
    }

    private Map<String, Object> entryMap(Path path, String type) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", path.getFileName().toString());
        map.put("type", type);
        map.put("size", "dir".equals(type) ? 0L : Files.size(path));
        map.put("modified", ISO.format(Files.getLastModifiedTime(path).toInstant()));
        return map;
    }
}
