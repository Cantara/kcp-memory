package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.TcpExchange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

/**
 * GET /synthesis/search?q=&lt;query&gt; — proxy search queries to local Synthesis instance.
 *
 * <p>Shells out to the configured Synthesis CLI command (default: {@code synthesis search}).
 * Returns the raw Synthesis output as JSON.
 */
public class SynthesisProxyHandler extends BaseHandler {

    private static final Logger LOG = Logger.getLogger(SynthesisProxyHandler.class.getName());

    private final String synthesisCommand;

    /**
     * @param synthesisCommand the CLI command to invoke, e.g. "synthesis search"
     */
    public SynthesisProxyHandler(String synthesisCommand) {
        this.synthesisCommand = synthesisCommand;
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }

        Map<String, String> params = queryParams(ex);
        String query = params.get("q");
        if (query == null || query.isBlank()) {
            sendError(ex, 400, "Missing required parameter: q");
            return;
        }

        int limit = 20;
        try {
            limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        } catch (NumberFormatException ignored) {
        }

        try {
            // Build command: synthesis search "query" --limit 20 --json
            ProcessBuilder pb = new ProcessBuilder(
                    "sh", "-c",
                    synthesisCommand + " \"" + escapeShell(query) + "\" --limit " + limit + " --json"
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                sendError(ex, 502, "Synthesis exited with code " + exitCode + ": " + output);
                return;
            }

            // Return Synthesis output directly as JSON
            String json = output.toString().trim();
            if (json.startsWith("{") || json.startsWith("[")) {
                ex.sendResponse(200, "application/json; charset=utf-8",
                        json.getBytes(StandardCharsets.UTF_8));
            } else {
                // Wrap plain text in JSON
                sendJson(ex, 200, Map.of("query", query, "raw_output", json));
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(ex, 500, "Synthesis query interrupted");
        } catch (Exception e) {
            LOG.warning("Synthesis proxy failed: " + e.getMessage());
            sendError(ex, 500, "Synthesis query failed: " + e.getMessage());
        }
    }

    /** Basic shell escaping — replace single quotes. */
    private static String escapeShell(String input) {
        return input.replace("'", "'\\''").replace("\"", "\\\"");
    }
}
