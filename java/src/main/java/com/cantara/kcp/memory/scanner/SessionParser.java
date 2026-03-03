package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.model.Session;
import com.cantara.kcp.memory.model.ToolUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses a Claude Code .jsonl session transcript into a {@link Session} and
 * a list of {@link ToolUsage} records.
 * <p>
 * Each line is a JSON object representing one turn in the conversation.
 * Relevant fields consumed:
 * <ul>
 *   <li>{@code sessionId}  — top-level, unique per session file</li>
 *   <li>{@code type}       — "human" | "assistant" | "tool_result" etc.</li>
 *   <li>{@code message}    — the actual content object (Claude API message format)</li>
 *   <li>{@code timestamp}  — ISO-8601 string</li>
 * </ul>
 */
public class SessionParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_FIRST_MSG = 500;
    private static final int MAX_ALL_USER  = 8000;

    /**
     * Parse one .jsonl file.
     *
     * @param jsonlPath path to the session file
     * @param slug      derived from the parent directory name in ~/.claude/projects/
     * @return parsed result, or empty if file is empty or unreadable
     */
    public Optional<ParseResult> parse(Path jsonlPath, String slug) {
        List<String> lines;
        try {
            lines = Files.readAllLines(jsonlPath);
        } catch (IOException e) {
            return Optional.empty();
        }

        String sessionId = jsonlPath.getFileName().toString().replace(".jsonl", "");
        String projectDir = null;
        String gitBranch  = null;
        String model      = null;
        String startedAt  = null;
        String endedAt    = null;
        int    turnCount  = 0;

        StringBuilder allUserText = new StringBuilder();
        String firstMessage = null;
        Set<String> toolNamesSet = new LinkedHashSet<>();
        Set<String> filesSet     = new LinkedHashSet<>();
        List<ToolUsage> usages   = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (Exception e) {
                continue;
            }

            // session-level fields (present on first line or consistently)
            if (projectDir == null && node.has("cwd")) {
                projectDir = node.get("cwd").asText();
            }
            if (node.has("sessionId") && sessionId.length() < 8) {
                sessionId = node.get("sessionId").asText();
            }
            if (gitBranch == null && node.has("gitBranch")) {
                gitBranch = node.get("gitBranch").asText();
            }

            String ts = node.has("timestamp") ? node.get("timestamp").asText() : null;
            if (ts != null) {
                if (startedAt == null) startedAt = ts;
                endedAt = ts;
            }

            String type = node.has("type") ? node.get("type").asText() : "";

            // Human turns → extract user text
            if ("human".equals(type)) {
                turnCount++;
                String text = extractText(node);
                if (text != null && !text.isBlank()) {
                    if (firstMessage == null) {
                        firstMessage = text.length() > MAX_FIRST_MSG
                                ? text.substring(0, MAX_FIRST_MSG)
                                : text;
                    }
                    if (allUserText.length() < MAX_ALL_USER) {
                        allUserText.append(text).append(' ');
                    }
                }
            }

            // Assistant turns → extract model, tool use
            if ("assistant".equals(type)) {
                turnCount++;
                JsonNode msg = node.get("message");
                if (msg != null) {
                    if (model == null && msg.has("model")) {
                        model = msg.get("model").asText();
                    }
                    JsonNode content = msg.get("content");
                    if (content != null && content.isArray()) {
                        for (JsonNode block : content) {
                            String blockType = block.has("type") ? block.get("type").asText() : "";
                            if ("tool_use".equals(blockType)) {
                                String toolName  = block.has("name") ? block.get("name").asText() : "unknown";
                                String toolInput = block.has("input") ? block.get("input").toString() : null;
                                toolNamesSet.add(toolName);
                                extractFilePaths(toolInput, filesSet);
                                usages.add(new ToolUsage(sessionId, toolName, toolInput, ts));
                            }
                        }
                    }
                }
            }
        }

        if (projectDir == null) {
            // Derive from file path: ~/.claude/projects/<slug>/<sessionId>.jsonl
            Path parent = jsonlPath.getParent();
            if (parent != null) {
                String dirName = parent.getFileName().toString();
                // dirName is the slug (URL-encoded path)
                projectDir = decodeSlug(dirName);
            }
        }

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
        session.setToolNames(new ArrayList<>(toolNamesSet));
        session.setFiles(new ArrayList<>(filesSet));
        session.setFirstMessage(firstMessage);
        session.setAllUserText(allUserText.toString().trim());
        session.setScannedAt(Instant.now().toString());

        return Optional.of(new ParseResult(session, usages));
    }

    private String extractText(JsonNode node) {
        // Handles both string content and array content blocks
        JsonNode msg = node.get("message");
        if (msg == null) return null;
        JsonNode content = msg.get("content");
        if (content == null) return null;
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if ("text".equals(block.path("type").asText()) && block.has("text")) {
                    sb.append(block.get("text").asText()).append(' ');
                }
            }
            return sb.toString().trim();
        }
        return null;
    }

    private void extractFilePaths(String toolInput, Set<String> filesSet) {
        if (toolInput == null) return;
        try {
            JsonNode input = MAPPER.readTree(toolInput);
            // Read tool: file_path
            if (input.has("file_path")) filesSet.add(input.get("file_path").asText());
            // Write/Edit tool: file_path
            // Bash tool: no file path
            // Glob tool: pattern is not a path
        } catch (Exception ignored) {}
    }

    /** Reverse Claude Code's URL-encoding of project paths used in directory slugs. */
    private String decodeSlug(String slug) {
        // Claude uses '-' as separator and encodes '/' as '-'
        // Best effort: replace first segment heuristic
        return slug.replace('-', '/');
    }

    public record ParseResult(Session session, List<ToolUsage> toolUsages) {}
}
