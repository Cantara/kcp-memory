package com.cantara.kcp.memory.scanner;

import com.cantara.kcp.memory.model.AgentSession;
import com.cantara.kcp.memory.store.AgentSessionStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * Scans subagent transcript files under ~/.claude/projects/ and indexes them
 * into the agent_sessions table.
 * <p>
 * Subagent files live at:
 *   ~/.claude/projects/{project-slug}/{session-uuid}/subagents/agent-{agentId}.jsonl
 * <p>
 * Each JSONL line has: sessionId (parent), agentId, slug, cwd, type (user/assistant),
 * message, timestamp.
 */
public class AgentSessionScanner {

    private static final Logger LOG = Logger.getLogger(AgentSessionScanner.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path DEFAULT_CLAUDE_PROJECTS =
            Path.of(System.getProperty("user.home"), ".claude", "projects");

    private static final int MAX_FIRST_MSG = 500;
    private static final int MAX_ALL_USER  = 8000;

    private final Path root;
    private final AgentSessionStore store;

    public AgentSessionScanner(MemoryDatabase db) {
        this(DEFAULT_CLAUDE_PROJECTS, db);
    }

    public AgentSessionScanner(Path root, MemoryDatabase db) {
        this.root  = root;
        this.store = new AgentSessionStore(db);
    }

    /**
     * Scan for subagent JSONL files and index new/changed ones.
     *
     * @param force if true, re-index all files regardless of modification time
     */
    public ScanResult scan(boolean force) {
        List<String> errors = new ArrayList<>();
        int indexed = 0, skipped = 0;

        try {
            List<Path> agentFiles = collectAgentFiles();
            for (Path file : agentFiles) {
                try {
                    boolean wasIndexed = indexFile(file, force);
                    if (wasIndexed) indexed++; else skipped++;
                } catch (Exception e) {
                    errors.add(file + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            errors.add("Walk error: " + e.getMessage());
        }

        LOG.info(String.format("Agent scan complete: %d indexed, %d skipped, %d errors",
                indexed, skipped, errors.size()));
        return new ScanResult(indexed, skipped, errors.size(), errors);
    }

    private boolean indexFile(Path file, boolean force) throws IOException, SQLException {
        AgentSession agent = parseAgentFile(file);
        if (agent == null) return false;

        if (!force && isCurrent(file, agent.getAgentId())) {
            return false;
        }

        agent.setScannedAt(Instant.now().toString());
        store.upsert(agent);
        return true;
    }

    private boolean isCurrent(Path file, String agentId) throws SQLException {
        String scannedAt = store.getScannedAt(agentId);
        if (scannedAt == null) return false;
        try {
            long fileModMs = Files.getLastModifiedTime(file).toMillis();
            long indexedMs = Instant.parse(scannedAt).toEpochMilli();
            return fileModMs <= indexedMs;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Parse a single agent-*.jsonl file into an AgentSession.
     */
    AgentSession parseAgentFile(Path file) throws IOException {
        String body = Files.readString(file);
        if (body.isBlank()) return null;

        String agentId = null;
        String parentSessionId = null;
        String agentSlug = null;
        String cwd = null;
        String projectDir = null;
        String model = null;
        String firstSeenAt = null;
        String lastUpdatedAt = null;
        String firstMessage = null;
        StringBuilder allUserText = new StringBuilder();
        Set<String> toolNames = new LinkedHashSet<>();
        int turnCount = 0;
        int toolCallCount = 0;
        int messageCount = 0;

        for (String rawLine : body.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            JsonNode node;
            try {
                node = MAPPER.readTree(line);
            } catch (Exception e) {
                continue; // skip malformed lines
            }

            messageCount++;

            // Extract identity fields from the first parseable line
            if (agentId == null && node.has("agentId")) {
                agentId = node.get("agentId").asText();
            }
            if (parentSessionId == null && node.has("sessionId")) {
                parentSessionId = node.get("sessionId").asText();
            }
            if (agentSlug == null && node.has("slug")) {
                agentSlug = node.get("slug").asText();
            }
            if (cwd == null && node.has("cwd")) {
                cwd = node.get("cwd").asText();
            }

            // Capture timestamps
            String ts = textField(node, "timestamp");
            if (ts != null) {
                if (firstSeenAt == null) firstSeenAt = ts;
                lastUpdatedAt = ts;
            }

            String type = textField(node, "type");

            if ("user".equals(type)) {
                String text = extractMessageText(node);
                // Only count user messages that are NOT tool results
                boolean isToolResult = false;
                JsonNode msgContent = node.path("message").path("content");
                if (msgContent.isArray()) {
                    for (JsonNode block : msgContent) {
                        if ("tool_result".equals(textField(block, "type"))) {
                            isToolResult = true;
                            break;
                        }
                    }
                }
                if (!isToolResult && text != null && !text.isBlank()) {
                    turnCount++;
                    if (firstMessage == null) {
                        firstMessage = text.length() > MAX_FIRST_MSG
                                ? text.substring(0, MAX_FIRST_MSG) : text;
                    }
                    if (allUserText.length() < MAX_ALL_USER) {
                        allUserText.append(text).append(' ');
                    }
                }
            } else if ("assistant".equals(type)) {
                turnCount++;
                // Extract model
                JsonNode message = node.path("message");
                if (model == null && message.has("model")) {
                    model = message.get("model").asText();
                }
                // Extract tool uses
                JsonNode content = message.path("content");
                if (content.isArray()) {
                    for (JsonNode block : content) {
                        if ("tool_use".equals(textField(block, "type"))) {
                            String toolName = block.has("name")
                                    ? block.get("name").asText() : "unknown";
                            toolNames.add(toolName);
                            toolCallCount++;
                        }
                    }
                }
            }
        }

        if (agentId == null) {
            // Fall back to extracting from filename: agent-<id>.jsonl
            String filename = file.getFileName().toString();
            if (filename.startsWith("agent-") && filename.endsWith(".jsonl")) {
                agentId = filename.substring(6, filename.length() - 6);
            }
        }

        if (agentId == null || parentSessionId == null) return null;

        // Derive projectDir from cwd if not already set
        if (projectDir == null) projectDir = cwd;

        AgentSession agent = new AgentSession();
        agent.setAgentId(agentId);
        agent.setParentSessionId(parentSessionId);
        agent.setAgentSlug(agentSlug);
        agent.setProjectDir(projectDir);
        agent.setCwd(cwd);
        agent.setModel(model);
        agent.setTurnCount(turnCount);
        agent.setToolCallCount(toolCallCount);
        agent.setToolNames(new ArrayList<>(toolNames));
        agent.setFirstMessage(firstMessage);
        agent.setAllUserText(allUserText.toString().trim());
        agent.setFirstSeenAt(firstSeenAt);
        agent.setLastUpdatedAt(lastUpdatedAt);
        agent.setMessageCount(messageCount);
        return agent;
    }

    /**
     * Extract user-facing text from a message node.
     * Handles both string content and array-of-blocks content.
     */
    private String extractMessageText(JsonNode node) {
        JsonNode msg = node.path("message");
        JsonNode content = msg.path("content");
        if (content.isTextual()) return content.asText();
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : content) {
                if (block.isTextual()) {
                    appendWithSpace(sb, block.asText());
                } else {
                    String blockType = textField(block, "type");
                    if ("text".equals(blockType)) {
                        appendWithSpace(sb, textField(block, "text"));
                    }
                    // Skip tool_result blocks — they are responses, not user intent
                }
            }
            return sb.toString().trim();
        }
        return textField(msg, "content");
    }

    private void appendWithSpace(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) return;
        if (!sb.isEmpty()) sb.append(' ');
        sb.append(text.trim());
    }

    private String textField(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || !node.has(field) || node.get(field).isNull())
            return null;
        return node.get(field).asText();
    }

    /**
     * Walk the Claude projects directory and collect all subagent JSONL files.
     */
    private List<Path> collectAgentFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(root)) return files;

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isSubagentFile(file)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                LOG.fine("Skipping unreadable file: " + file);
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /**
     * Check if a file is a subagent transcript:
     * path contains /subagents/ and filename matches agent-*.jsonl
     */
    static boolean isSubagentFile(Path file) {
        String name = file.getFileName().toString();
        if (!name.startsWith("agent-") || !name.endsWith(".jsonl")) return false;
        // Verify it's inside a subagents/ directory
        Path parent = file.getParent();
        return parent != null && parent.getFileName() != null
                && "subagents".equals(parent.getFileName().toString());
    }

    public record ScanResult(int indexed, int skipped, int errors, List<String> errorMessages) {
        public boolean hasErrors() { return errors > 0; }
    }
}
