package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.TcpExchange;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * POST /process — run systemctl actions on named services.
 *
 * <p>Security constraints:
 * <ul>
 *   <li>Only alphanumeric service names (plus {@code -}, {@code _}, {@code .})</li>
 *   <li>Only allowed actions: status, start, stop, restart</li>
 *   <li>Returns 503 if systemctl is not available</li>
 * </ul>
 *
 * <h3>Android client reference</h3>
 * <p>{@code POST /process} body JSON &rarr; {@code ProcessResponse(service, action, output)}</p>
 */
public class ProcessHandler extends BaseHandler {

    private static final Logger LOG = Logger.getLogger(ProcessHandler.class.getName());
    private static final Pattern SAFE_SERVICE = Pattern.compile("[a-zA-Z0-9._-]+");
    private static final Set<String> ALLOWED_ACTIONS = Set.of("status", "start", "stop", "restart");

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }

        if (!isSystemctlAvailable()) {
            sendError(ex, 503, "systemctl not available");
            return;
        }

        String body = readBody(ex);
        if (body == null || body.isBlank()) {
            sendError(ex, 400, "Request body is required");
            return;
        }

        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (Exception e) {
            sendError(ex, 400, "Invalid JSON: " + e.getMessage());
            return;
        }

        String action = json.path("action").asText(null);
        String service = json.path("service").asText(null);

        if (action == null || action.isBlank()) {
            sendError(ex, 400, "Missing required field: action");
            return;
        }
        if (service == null || service.isBlank()) {
            sendError(ex, 400, "Missing required field: service");
            return;
        }

        if (!ALLOWED_ACTIONS.contains(action)) {
            sendError(ex, 400, "Invalid action: " + action + ". Allowed: " + ALLOWED_ACTIONS);
            return;
        }

        if (!SAFE_SERVICE.matcher(service).matches()) {
            sendError(ex, 400, "Invalid service name: " + service);
            return;
        }

        try {
            String output = runSystemctl(action, service);
            sendJson(ex, 200, Map.of(
                    "service", service,
                    "action", action,
                    "output", output
            ));
        } catch (IOException e) {
            LOG.warning("systemctl failed: " + e.getMessage());
            sendError(ex, 500, "systemctl failed: " + e.getMessage());
        }
    }

    /**
     * Run systemctl with the given action and service.
     * Package-private for testability (override in tests).
     */
    String runSystemctl(String action, String service) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("systemctl", action, service);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try {
            output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("systemctl timed out after 10 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("systemctl interrupted", e);
        }

        return output;
    }

    /**
     * Check if systemctl is available on this system.
     * Package-private for testability (override in tests).
     */
    boolean isSystemctlAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            return false;
        }
        return Files.isExecutable(Path.of("/usr/bin/systemctl"))
                || Files.isExecutable(Path.of("/bin/systemctl"));
    }
}
