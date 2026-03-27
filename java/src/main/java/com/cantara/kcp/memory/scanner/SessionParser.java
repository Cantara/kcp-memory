package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.model.Session;
import com.cantara.kcp.memory.model.ToolUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Parses supported session transcript formats into a {@link Session} and
 * a list of {@link ToolUsage} records.
 */
public class SessionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_FIRST_MSG = 500;
    private static final int MAX_ALL_USER = 8000;

    public Optional<ParseResult> parse(Path sessionPath, String slug) {
        try {
            String body = Files.readString(sessionPath);
            if (body.isBlank()) {
                return Optional.of(emptyResult(defaultSessionId(sessionPath), slug, sessionPath));
            }

            if (isGeminiSession(sessionPath)) {
                return parseGemini(sessionPath, slug, body);
            }
            if (isCodexSession(sessionPath, body)) {
                return parseCodex(sessionPath, slug, body);
            }
            return parseClaude(sessionPath, slug, body);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<ParseResult> parseClaude(Path path, String slug, String body) {
        SessionAccumulator acc = new SessionAccumulator(defaultSessionId(path), slug, path);
        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            JsonNode node = readJson(line);
            if (node == null) continue;

            if (acc.projectDir == null && node.has("cwd")) {
                acc.projectDir = node.get("cwd").asText();
            }
            if (node.has("sessionId") && !node.get("sessionId").asText().isBlank()) {
                acc.sessionId = node.get("sessionId").asText();
            }
            if (acc.gitBranch == null && node.has("gitBranch")) {
                acc.gitBranch = node.get("gitBranch").asText();
            }

            String ts = text(node, "timestamp");
            acc.captureTimestamp(ts);

            String type = text(node, "type");
            if ("human".equals(type) || "user".equals(type)) {
                acc.addUserText(extractClaudeMessageText(node));
            } else if ("assistant".equals(type)) {
                JsonNode message = node.path("message");
                if (acc.model == null && message.has("model")) {
                    acc.model = message.get("model").asText();
                }
                JsonNode content = message.path("content");
                if (content.isArray()) {
                    for (JsonNode block : content) {
                        if ("tool_use".equals(text(block, "type"))) {
                            String toolName = block.has("name") ? block.get("name").asText() : "unknown";
                            String toolInput = block.has("input") ? block.get("input").toString() : null;
                            acc.addTool(toolName, toolInput, ts);
                        }
                    }
                }
                acc.turnCount++;
            }
        }

        if (acc.projectDir == null) {
            Path parent = path.getParent();
            if (parent != null && parent.getFileName() != null) {
                acc.projectDir = decodeClaudeSlug(parent.getFileName().toString());
            }
        }
        return Optional.of(acc.toResult());
    }

    private Optional<ParseResult> parseGemini(Path path, String slug, String body) {
        JsonNode root = readJson(body);
        if (root == null || !root.isObject()) return Optional.empty();

        SessionAccumulator acc = new SessionAccumulator(
                root.path("sessionId").asText(defaultSessionId(path)),
                slug,
                path
        );
        acc.projectDir = decodeGeminiProject(path, slug);
        acc.model = findGeminiModel(root);
        acc.startedAt = text(root, "startTime");
        acc.endedAt = text(root, "lastUpdated");

        JsonNode messages = root.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                String type = text(message, "type");
                String ts = text(message, "timestamp");
                acc.captureTimestamp(ts);

                if ("user".equals(type)) {
                    acc.addUserText(extractGeminiText(message.get("content")));
                    continue;
                }
                if ("gemini".equals(type)) {
                    acc.turnCount++;
                    acc.collectGeminiTools(message.path("toolCalls"), ts);
                }
            }
        }

        return Optional.of(acc.toResult());
    }

    private Optional<ParseResult> parseCodex(Path path, String slug, String body) {
        SessionAccumulator acc = new SessionAccumulator(defaultSessionId(path), slug, path);

        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            JsonNode node = readJson(line);
            if (node == null) continue;

            String type = text(node, "type");
            JsonNode payload = node.path("payload");
            String ts = text(node, "timestamp");
            acc.captureTimestamp(ts);

            if ("session_meta".equals(type)) {
                if (payload.has("id")) acc.sessionId = payload.get("id").asText();
                if (payload.has("cwd")) acc.projectDir = payload.get("cwd").asText();
                if (payload.has("git")) {
                    acc.gitBranch = text(payload.path("git"), "branch");
                }
                if (payload.has("model")) {
                    acc.model = payload.get("model").asText();
                } else if (payload.has("originator")) {
                    acc.model = payload.get("originator").asText();
                }
                String payloadTs = text(payload, "timestamp");
                if (payloadTs != null) acc.captureTimestamp(payloadTs);
                continue;
            }

            if ("event_msg".equals(type)) {
                String eventType = text(payload, "type");
                if ("user_message".equals(eventType)) {
                    acc.addUserText(text(payload, "message"));
                } else if ("agent_message".equals(eventType)) {
                    acc.turnCount++;
                }
                continue;
            }

            if ("response_item".equals(type)) {
                String itemType = text(payload, "type");
                if ("message".equals(itemType)) {
                    String role = text(payload, "role");
                    if ("user".equals(role)) {
                        acc.addUserText(extractCodexContent(payload.path("content")));
                    } else if ("assistant".equals(role) || "developer".equals(role)) {
                        acc.turnCount++;
                    }
                } else if ("function_call".equals(itemType)) {
                    String toolName = text(payload, "name");
                    String toolInput = payload.has("arguments") ? payload.get("arguments").asText() : null;
                    acc.addTool(toolName != null ? toolName : "unknown", toolInput, ts);
                }
            }
        }

        if (acc.projectDir == null) {
            acc.projectDir = slug;
        }
        return Optional.of(acc.toResult());
    }

    private ParseResult emptyResult(String sessionId, String slug, Path path) {
        return new SessionAccumulator(sessionId, slug, path).toResult();
    }

    private boolean isGeminiSession(Path path) {
        return path.getFileName().toString().startsWith("session-") && path.toString().endsWith(".json");
    }

    private boolean isCodexSession(Path path, String body) {
        if (path.getFileName().toString().startsWith("rollout-")) return true;
        String firstLine = body.lines().findFirst().orElse("");
        JsonNode first = readJson(firstLine);
        return first != null && "session_meta".equals(text(first, "type"));
    }

    private JsonNode readJson(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractClaudeMessageText(JsonNode node) {
        JsonNode msg = node.path("message");
        JsonNode content = msg.path("content");
        return extractTextBlocks(content);
    }

    private String extractGeminiText(JsonNode content) {
        if (content == null || content.isMissingNode()) return null;
        if (content.isTextual()) return content.asText();
        if (content.isArray()) return extractTextBlocks(content);
        return null;
    }

    private String extractCodexContent(JsonNode content) {
        if (content == null || content.isMissingNode()) return null;
        if (content.isTextual()) return content.asText();

        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode item : content) {
                String type = text(item, "type");
                if ("input_text".equals(type) || "output_text".equals(type) || "text".equals(type)) {
                    appendWithSpace(sb, text(item, "text"));
                }
            }
        }
        return sb.toString().trim();
    }

    private String extractTextBlocks(JsonNode content) {
        if (content == null || content.isMissingNode()) return null;
        if (content.isTextual()) return content.asText();

        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode block : content) {
                if (block.isTextual()) {
                    appendWithSpace(sb, block.asText());
                    continue;
                }
                appendWithSpace(sb, text(block, "text"));
            }
        }
        return sb.toString().trim();
    }

    private void appendWithSpace(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) return;
        if (!sb.isEmpty()) sb.append(' ');
        sb.append(text.trim());
    }

    private String findGeminiModel(JsonNode root) {
        JsonNode messages = root.path("messages");
        if (!messages.isArray()) return null;
        for (JsonNode message : messages) {
            if (message.has("model")) return message.get("model").asText();
            if (message.has("type") && "gemini".equals(message.get("type").asText())) {
                return "gemini";
            }
        }
        return null;
    }

    private String decodeGeminiProject(Path path, String slug) {
        if (slug != null && !slug.isBlank() && !looksLikeHash(slug)) {
            return slug;
        }
        Path parent = path.getParent();
        if (parent != null && parent.getParent() != null && parent.getParent().getFileName() != null) {
            return parent.getParent().getFileName().toString();
        }
        return slug != null ? slug : "";
    }

    private boolean looksLikeHash(String value) {
        return value != null && value.matches("[a-fA-F0-9]{16,}");
    }

    private String decodeClaudeSlug(String slug) {
        return slug.replace('-', '/');
    }

    private String defaultSessionId(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }

    public record ParseResult(Session session, List<ToolUsage> toolUsages) {}

    private static final class SessionAccumulator {
        private final Path sourcePath;
        private String sessionId;
        private String projectDir;
        private String gitBranch;
        private String slug;
        private String model;
        private String startedAt;
        private String endedAt;
        private int turnCount;
        private final StringBuilder allUserText = new StringBuilder();
        private String firstMessage;
        private final Set<String> toolNames = new LinkedHashSet<>();
        private final Set<String> files = new LinkedHashSet<>();
        private final List<ToolUsage> usages = new ArrayList<>();

        private SessionAccumulator(String sessionId, String slug, Path sourcePath) {
            this.sessionId = sessionId;
            this.slug = slug;
            this.sourcePath = sourcePath;
        }

        private void captureTimestamp(String ts) {
            if (ts == null || ts.isBlank()) return;
            if (startedAt == null) startedAt = ts;
            endedAt = ts;
        }

        private void addUserText(String text) {
            if (text == null || text.isBlank()) return;
            turnCount++;
            if (firstMessage == null) {
                firstMessage = text.length() > MAX_FIRST_MSG ? text.substring(0, MAX_FIRST_MSG) : text;
            }
            if (allUserText.length() < MAX_ALL_USER) {
                allUserText.append(text).append(' ');
            }
        }

        private void addTool(String toolName, String toolInput, String ts) {
            toolNames.add(toolName);
            extractFilePaths(toolInput, files);
            usages.add(new ToolUsage(sessionId, toolName, toolInput, ts));
        }

        private void collectGeminiTools(JsonNode toolCalls, String ts) {
            if (!toolCalls.isArray()) return;
            for (JsonNode toolCall : toolCalls) {
                String name = null;
                String input = null;
                if (toolCall.has("name")) name = toolCall.get("name").asText();
                if (toolCall.has("args")) input = toolCall.get("args").toString();
                if (toolCall.has("arguments")) input = toolCall.get("arguments").toString();
                addTool(name != null ? name : "unknown", input, ts);
            }
        }

        private ParseResult toResult() {
            Session session = new Session();
            session.setSessionId(sessionId);
            session.setProjectDir(projectDir != null ? projectDir : "");
            session.setGitBranch(gitBranch);
            session.setSlug(slug);
            session.setModel(model);
            session.setStartedAt(startedAt);
            session.setEndedAt(endedAt);
            session.setTurnCount(turnCount);
            session.setToolCallCount(usages.size());
            session.setToolNames(new ArrayList<>(toolNames));
            session.setFiles(new ArrayList<>(files));
            session.setFirstMessage(firstMessage);
            session.setAllUserText(allUserText.toString().trim());
            session.setScannedAt(Instant.now().toString());
            return new ParseResult(session, usages);
        }

        private void extractFilePaths(String toolInput, Set<String> filesSet) {
            if (toolInput == null || toolInput.isBlank()) return;
            try {
                JsonNode input = MAPPER.readTree(toolInput);
                collectFilePath(input, filesSet);
            } catch (Exception ignored) {
            }
        }

        private void collectFilePath(JsonNode node, Set<String> filesSet) {
            if (node == null || node.isMissingNode()) return;
            if (node.isObject()) {
                if (node.has("file_path")) filesSet.add(node.get("file_path").asText());
                if (node.has("path")) filesSet.add(node.get("path").asText());
                node.elements().forEachRemaining(child -> collectFilePath(child, filesSet));
                return;
            }
            if (node.isArray()) {
                node.forEach(child -> collectFilePath(child, filesSet));
            }
        }
    }
}
