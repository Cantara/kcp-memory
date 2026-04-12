package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.server.TcpExchange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * POST /dispatch — send a task to Claude Code and stream the results.
 *
 * <p>Request body:
 * <pre>
 * {
 *   "prompt": "Run the test suite for lib-pcb",
 *   "allowed_tools": ["Bash", "Read", "Glob", "Grep"],
 *   "working_dir": "/home/ec2-user/projects/lib-pcb"
 * }
 * </pre>
 *
 * <p>Response: streams {@code claude -p --output-format stream-json} output
 * line-by-line as chunked transfer encoding. This lets the mobile client
 * show progress in real time.
 */
public class DispatchHandler extends BaseHandler {

    private static final Logger LOG = Logger.getLogger(DispatchHandler.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }

        // Parse request body
        JsonNode body;
        try {
            body = JSON.readTree(ex.getRequestBody());
        } catch (Exception e) {
            sendError(ex, 400, "Invalid JSON body");
            return;
        }

        String prompt = body.path("prompt").asText(null);
        if (prompt == null || prompt.isBlank()) {
            sendError(ex, 400, "Missing required field: prompt");
            return;
        }

        String workingDir = body.path("working_dir").asText(System.getProperty("user.home"));

        // Build allowed tools list
        List<String> allowedTools = new ArrayList<>();
        JsonNode toolsNode = body.get("allowed_tools");
        if (toolsNode != null && toolsNode.isArray()) {
            for (JsonNode tool : toolsNode) {
                allowedTools.add(tool.asText());
            }
        }
        if (allowedTools.isEmpty()) {
            allowedTools = List.of("Read", "Glob", "Grep");  // safe default: read-only
        }

        // Build claude command
        List<String> cmd = new ArrayList<>();
        cmd.add("claude");
        cmd.add("-p");
        cmd.add(prompt);
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        cmd.add("--allowedTools");
        cmd.add(String.join(",", allowedTools));

        LOG.info("Dispatching task: " + prompt.substring(0, Math.min(prompt.length(), 80)));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new java.io.File(workingDir));
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Stream output line by line
            ex.sendStreamingHeaders(200, "application/x-ndjson; charset=utf-8");
            OutputStream out = ex.getOutputStream();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                    out.flush();
                }
            }

            int exitCode = process.waitFor();
            // Send a final status line
            String status = "{\"type\":\"dispatch_complete\",\"exit_code\":" + exitCode + "}\n";
            out.write(status.getBytes(StandardCharsets.UTF_8));
            out.flush();

            LOG.info("Dispatch completed with exit code " + exitCode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(ex, 500, "Task interrupted");
        } catch (Exception e) {
            LOG.warning("Dispatch failed: " + e.getMessage());
            sendError(ex, 500, "Dispatch failed: " + e.getMessage());
        }
    }
}
