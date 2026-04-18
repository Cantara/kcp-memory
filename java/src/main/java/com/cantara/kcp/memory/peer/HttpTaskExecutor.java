package com.cantara.kcp.memory.peer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * TaskExecutor that calls an OpenAI-compatible chat completions endpoint.
 *
 * <p>Used for IronClaw nodes (Klaw/Mimir) that run Deepseek/Qwen via OpenRouter
 * instead of the {@code claude} CLI.
 *
 * <p>Configure via constructor (read from env in {@link DaemonCmd}):
 * <ul>
 *   <li>{@code EXECUTOR_BASE_URL}  — e.g. {@code https://openrouter.ai/api/v1}</li>
 *   <li>{@code EXECUTOR_API_KEY}   — Bearer token</li>
 *   <li>{@code EXECUTOR_MODEL}     — e.g. {@code deepseek/deepseek-v3.2}</li>
 * </ul>
 */
public class HttpTaskExecutor implements TaskExecutor {

    private static final Logger LOG = Logger.getLogger(HttpTaskExecutor.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 120;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    public HttpTaskExecutor(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String execute(String prompt, String systemPrompt) throws IOException {
        LOG.info("Dispatching to " + model + ": " + prompt.substring(0, Math.min(prompt.length(), 80)));

        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        ArrayNode messages = body.putArray("messages");
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.addObject()
                    .put("role", "system")
                    .put("content", systemPrompt);
        }
        messages.addObject()
                .put("role", "user")
                .put("content", prompt);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP executor interrupted", e);
        }

        if (response.statusCode() != 200) {
            throw new IOException("LLM API returned " + response.statusCode() + ": "
                    + response.body().substring(0, Math.min(response.body().length(), 200)));
        }

        JsonNode root = JSON.readTree(response.body());
        String content = root.path("choices").path(0).path("message").path("content").asText(null);
        if (content == null) {
            throw new IOException("Unexpected LLM response format: "
                    + response.body().substring(0, Math.min(response.body().length(), 200)));
        }
        return content;
    }
}
