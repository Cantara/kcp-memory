package com.cantara.kcp.memory.peer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Default TaskExecutor that runs {@code claude -p "<prompt>" --output-format text}.
 *
 * <p>Captures stdout with a 5-minute timeout.
 */
public class ClaudeTaskExecutor implements TaskExecutor {

    private static final Logger LOG = Logger.getLogger(ClaudeTaskExecutor.class.getName());
    private static final long TIMEOUT_MINUTES = 5;

    @Override
    public String execute(String prompt, String systemPrompt) throws IOException {
        List<String> cmd = new java.util.ArrayList<>(List.of("claude", "-p", prompt, "--output-format", "text"));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            cmd.add("--system-prompt");
            cmd.add(systemPrompt);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        LOG.info("Executing task: " + prompt.substring(0, Math.min(prompt.length(), 80)));
        Process process = pb.start();
        process.getOutputStream().close(); // close stdin so claude doesn't block waiting for EOF

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        try {
            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Task timed out after " + TIMEOUT_MINUTES + " minutes");
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("claude exited with code " + exitCode + ": " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Task interrupted", e);
        }

        return output.toString().trim();
    }
}
