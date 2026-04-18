package com.cantara.kcp.memory.peer;

import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.PeerCursorStore;
import com.cantara.kcp.memory.store.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Bidirectional sync service between kcp-memory instances.
 *
 * <p>Each sync cycle does two things:
 * <ol>
 *   <li><b>Pull</b> — fetch new sessions/events from the remote peer's HTTP API</li>
 *   <li><b>Push</b> — POST local sessions/events to the remote peer's {@code /ingest} endpoint</li>
 * </ol>
 *
 * <p>This enables hub-and-spoke topology: a laptop behind NAT initiates an outbound
 * SSH tunnel and both pulls and pushes through it. The hub (EC2-A) never needs
 * to reach the laptop directly.
 *
 * <p><b>Transitive sync:</b> events received from one peer carry their original
 * {@code source_instance} tag. When served to another peer, they flow through
 * unchanged. The {@code event_hash} unique index prevents duplicates regardless
 * of which path an event arrives through.
 *
 * <p>Supports two transport modes:
 * <ul>
 *   <li>{@code ssh://user@host} — managed SSH tunnel (via {@link SshTunnel})</li>
 *   <li>{@code tcp://host:port} — direct TCP (trusted network)</li>
 * </ul>
 */
public class PeerSyncService {

    private static final Logger LOG = Logger.getLogger(PeerSyncService.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int SYNC_INTERVAL_SECONDS = 30;

    private final MemoryDatabase db;
    private final PeerCursorStore cursorStore;
    private final String peerUri;
    private final String localInstanceId;
    private String displayName;

    private SshTunnel tunnel;
    private String baseUrl;
    private String peerId;
    private HttpClient httpClient;
    private ScheduledExecutorService scheduler;
    private NodeRegistry nodeRegistry;
    private TaskExecutor taskExecutor;
    private List<String> capabilities = List.of();

    /**
     * @param db              local database
     * @param peerUri         ssh://user@host or tcp://host:port
     * @param localInstanceId identifier for this instance (e.g., hostname)
     */
    public PeerSyncService(MemoryDatabase db, String peerUri, String localInstanceId) {
        this.db = db;
        this.cursorStore = new PeerCursorStore(db);
        this.peerUri = peerUri;
        this.localInstanceId = localInstanceId;
    }

    /** Start the sync service. Establishes tunnel if SSH, then starts polling. */
    public void start() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        if (peerUri.startsWith("ssh://")) {
            tunnel = SshTunnel.fromUri(peerUri);
            tunnel.start();
            baseUrl = "http://127.0.0.1:" + tunnel.getLocalPort();
            peerId = tunnel.getPeerId();
            LOG.info("Peer sync via SSH tunnel: " + peerId + " -> " + baseUrl);
        } else if (peerUri.startsWith("tcp://")) {
            String hostPort = peerUri.substring("tcp://".length());
            baseUrl = "http://" + hostPort;
            peerId = hostPort;
            LOG.info("Peer sync via direct TCP: " + baseUrl);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported peer URI scheme. Use ssh://user@host or tcp://host:port");
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kcp-peer-sync-" + peerId);
            t.setDaemon(true);
            return t;
        });

        // Initial sync after 5s (let tunnel establish), then every 30s
        scheduler.scheduleAtFixedRate(this::syncOnce, 5, SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOG.info("Peer sync scheduled every " + SYNC_INTERVAL_SECONDS + "s with " + peerId);
    }

    /** Stop the sync service and close the tunnel. */
    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (tunnel != null) tunnel.stop();
        LOG.info("Peer sync stopped for " + peerId);
    }

    public boolean isConnected() {
        return tunnel == null || tunnel.isConnected();
    }

    public String getPeerId() {
        return peerId;
    }

    /**
     * Set a NodeRegistry to track peer health and presence.
     * After each successful sync cycle, the peer is marked as seen and
     * health metrics are updated.
     */
    public void setNodeRegistry(NodeRegistry registry) {
        this.nodeRegistry = registry;
    }

    /**
     * Set a friendly display name for this local node (used in self-registration payloads).
     * Defaults to localInstanceId if not set.
     */
    public void setDisplayName(String name) {
        this.displayName = name;
    }

    /**
     * Set a TaskExecutor for executing pending tasks polled from the hub.
     * Default: {@link ClaudeTaskExecutor}. Override in tests.
     */
    public void setTaskExecutor(TaskExecutor executor) {
        this.taskExecutor = executor;
    }

    /**
     * Set capabilities advertised by this node in self-registration payloads.
     * E.g. ["claude"] for Claude Code nodes, ["ironclaw", "deepseek/deepseek-v3.2"] for IronClaw.
     */
    public void setCapabilities(List<String> capabilities) {
        this.capabilities = capabilities != null ? capabilities : List.of();
    }

    /** This instance's identifier (hostname or configured ID). */
    public String getLocalInstanceId() {
        return localInstanceId;
    }

    // --- sync cycle ---

    private void syncOnce() {
        try {
            pullSessions();
            pullEvents();
            pushSessions();
            pushEvents();
            pollPendingTasks();

            // Update node registry after successful sync
            if (nodeRegistry != null && peerId != null) {
                nodeRegistry.register(peerId, peerUri);
                nodeRegistry.markSeen(peerId);
                // Fetch peer health stats
                try {
                    JsonNode health = fetchJson(baseUrl + "/health");
                    if (health != null) {
                        long sessions = health.path("sessions").asLong(0);
                        // Use sessions as sessionCount; eventCount from stats if available
                        nodeRegistry.updateHealth(peerId, sessions, 0);
                    }
                } catch (Exception e) {
                    LOG.fine("Could not fetch peer health for registry: " + e.getMessage());
                }
            }

            // Self-register this local peer on the hub so its /nodes endpoint reflects us
            try {
                long localSessions = 0;
                try { localSessions = new SessionStore(db).stats().totalSessions(); } catch (Exception ignored) {}
                String effectiveDisplayName = (displayName != null && !displayName.isBlank())
                        ? displayName : localInstanceId;
                ObjectNode registerNode = JSON.createObjectNode()
                        .put("peerId", localInstanceId)
                        .put("displayName", effectiveDisplayName)
                        .put("address", peerUri)
                        .put("sessionCount", localSessions);
                if (!capabilities.isEmpty()) {
                    com.fasterxml.jackson.databind.node.ArrayNode capsArray = registerNode.putArray("capabilities");
                    capabilities.forEach(capsArray::add);
                }
                postJson(baseUrl + "/nodes/register", JSON.writeValueAsString(registerNode));
            } catch (Exception e) {
                LOG.fine("Could not self-register with hub: " + e.getMessage());
            }
        } catch (Exception e) {
            LOG.warning("Peer sync failed for " + peerId + ": " + e.getMessage());
        }
    }

    // --- PENDING TASKS: poll hub for tasks assigned to this peer ---

    private void pollPendingTasks() {
        try {
            String url = baseUrl + "/pending?peer=" + localInstanceId;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204) {
                // Nothing pending
                return;
            }
            if (response.statusCode() != 200) {
                LOG.fine("Pending task poll returned " + response.statusCode());
                return;
            }

            JsonNode task = JSON.readTree(response.body());
            String taskId = task.path("taskId").asText(null);
            String prompt = task.path("prompt").asText(null);
            String systemPrompt = task.has("systemPrompt") && !task.get("systemPrompt").isNull()
                    ? task.get("systemPrompt").asText(null) : null;

            if (taskId == null || prompt == null) return;

            LOG.info("Claimed pending task " + taskId + ": " + prompt.substring(0, Math.min(prompt.length(), 80)));

            // Execute the task
            TaskExecutor executor = this.taskExecutor != null ? this.taskExecutor : new ClaudeTaskExecutor();
            String result;
            try {
                result = executor.execute(prompt, systemPrompt);
            } catch (IOException e) {
                LOG.warning("Task " + taskId + " failed: " + e.getMessage());
                // Push error back
                String errorPayload = JSON.writeValueAsString(
                        JSON.createObjectNode().put("taskId", taskId).put("error", e.getMessage()));
                postJson(baseUrl + "/pending/result", errorPayload);
                return;
            }

            // Push result back
            String resultPayload = JSON.writeValueAsString(
                    JSON.createObjectNode().put("taskId", taskId).put("result", result));
            postJson(baseUrl + "/pending/result", resultPayload);
            LOG.info("Task " + taskId + " completed successfully");

        } catch (Exception e) {
            LOG.fine("Pending task poll error: " + e.getMessage());
        }
    }

    // --- PULL: fetch from remote, merge locally ---

    private void pullSessions() throws IOException, InterruptedException, SQLException {
        String since = cursorStore.getLastSessionTs(peerId);
        String url = baseUrl + "/sessions?limit=100"
                + (since != null ? "&since=" + since : "");

        JsonNode response = fetchJson(url);
        if (response == null) return;

        JsonNode sessions = response.get("sessions");
        if (sessions == null || !sessions.isArray() || sessions.isEmpty()) return;

        String latestTs = null;
        int count = 0;

        for (JsonNode session : sessions) {
            // Jackson serializes SearchResult as camelCase (getSessionId -> sessionId)
            String sessionId = session.path("sessionId").asText(
                    session.path("session_id").asText(null));
            String startedAt = session.path("startedAt").asText(
                    session.path("started_at").asText(null));
            String projectDir = session.path("projectDir").asText(
                    session.path("project_dir").asText(""));
            String firstMessage = session.path("firstMessage").asText(
                    session.path("first_message").asText(""));
            int turnCount = session.has("turnCount") ? session.path("turnCount").asInt(0)
                    : session.path("turn_count").asInt(0);
            int toolCallCount = session.has("toolCallCount") ? session.path("toolCallCount").asInt(0)
                    : session.path("tool_call_count").asInt(0);
            // Preserve original source — enables transitive sync
            String source = session.path("source_instance").asText(peerId);
            if ("local".equals(source)) source = peerId;
            String slug = session.path("slug").asText(null);
            String gitBranch = session.path("git_branch").asText(null);
            String model = session.path("model").asText(null);
            String endedAt = session.path("ended_at").asText(null);
            String sessionTagsJson = session.path("session_tags").asText(null);
            if (sessionTagsJson != null && sessionTagsJson.equals("null")) sessionTagsJson = null;

            if (sessionId == null || startedAt == null) continue;

            try (PreparedStatement ps = db.getConnection().prepareStatement("""
                    INSERT INTO sessions
                        (session_id, project_dir, first_message, started_at,
                         turn_count, tool_call_count, scanned_at, source_instance,
                         slug, git_branch, model, ended_at, session_tags)
                    VALUES (?, ?, ?, ?, ?, ?, datetime('now'), ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET
                        turn_count = MAX(turn_count, excluded.turn_count),
                        tool_call_count = MAX(tool_call_count, excluded.tool_call_count),
                        slug = COALESCE(excluded.slug, slug),
                        git_branch = COALESCE(excluded.git_branch, git_branch),
                        model = COALESCE(excluded.model, model),
                        ended_at = COALESCE(excluded.ended_at, ended_at),
                        session_tags = CASE
                            WHEN excluded.session_tags IS NULL THEN session_tags
                            WHEN session_tags IS NULL THEN excluded.session_tags
                            ELSE (
                                SELECT json_group_array(value)
                                FROM (
                                    SELECT DISTINCT value
                                    FROM (
                                        SELECT value FROM json_each(session_tags)
                                        UNION ALL
                                        SELECT value FROM json_each(excluded.session_tags)
                                    )
                                )
                            )
                        END
                    """)) {
                ps.setString(1, sessionId);
                ps.setString(2, projectDir);
                ps.setString(3, firstMessage);
                ps.setString(4, startedAt);
                ps.setInt(5, turnCount);
                ps.setInt(6, toolCallCount);
                ps.setString(7, source);
                ps.setString(8, slug);
                ps.setString(9, gitBranch);
                ps.setString(10, model);
                ps.setString(11, endedAt);
                ps.setString(12, sessionTagsJson);
                ps.executeUpdate();
            }

            latestTs = startedAt;
            count++;
        }

        if (latestTs != null) {
            cursorStore.updateSessionCursor(peerId, latestTs);
            LOG.fine("Pulled " + count + " sessions from " + peerId);
        }
    }

    private void pullEvents() throws IOException, InterruptedException, SQLException {
        String since = cursorStore.getLastEventTs(peerId);
        String url = baseUrl + "/events/search?limit=200"
                + (since != null ? "&since=" + since : "");

        JsonNode response = fetchJson(url);
        if (response == null) return;

        // EventsHandler returns a JSON array directly (not wrapped in "events")
        JsonNode events = response;
        if (!events.isArray()) {
            events = response.get("events");
            if (events == null) events = response.get("results");
        }
        if (events == null || !events.isArray() || events.isEmpty()) return;

        String latestTs = null;
        int count = 0;

        for (JsonNode event : events) {
            // Jackson record serialization uses camelCase (eventTs, sessionId, projectDir)
            String eventTs = event.path("eventTs").asText(
                    event.path("event_ts").asText(null));
            String tool = event.path("tool").asText("Bash");
            String command = event.path("command").asText("");
            String sessionId = event.path("sessionId").asText(
                    event.path("session_id").asText(""));
            String projectDir = event.path("projectDir").asText(
                    event.path("project_dir").asText(""));
            String outputPreview = event.path("outputPreview").asText(
                    event.path("output_preview").asText(null));
            String source = event.path("source_instance").asText(peerId);
            if ("local".equals(source)) source = peerId;

            // Use provided hash or compute it — deduplicates across any sync path
            String hash = event.path("event_hash").asText(
                    event.path("eventHash").asText(null));
            if (hash == null) hash = computeEventHash(eventTs, tool, command, sessionId);

            if (eventTs == null) continue;

            try (PreparedStatement ps = db.getConnection().prepareStatement("""
                    INSERT OR IGNORE INTO tool_events
                        (event_ts, session_id, project_dir, tool, command,
                         output_preview, ingested_at, source_instance, event_hash)
                    VALUES (?, ?, ?, ?, ?, ?, datetime('now'), ?, ?)
                    """)) {
                ps.setString(1, eventTs);
                ps.setString(2, sessionId);
                ps.setString(3, projectDir);
                ps.setString(4, tool);
                ps.setString(5, command);
                ps.setString(6, outputPreview);
                ps.setString(7, source);
                ps.setString(8, hash);
                ps.executeUpdate();
            }

            latestTs = eventTs;
            count++;
        }

        if (latestTs != null) {
            cursorStore.updateEventCursor(peerId, latestTs);
            LOG.fine("Pulled " + count + " events from " + peerId);
        }
    }

    // --- PUSH: send local data to remote ---

    private void pushSessions() throws IOException, InterruptedException, SQLException {
        String since = cursorStore.getLastPushSessionTs(peerId);
        String whereClause = since != null ? " WHERE started_at > ?" : "";

        List<JsonNode> localSessions = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "SELECT session_id, project_dir, first_message, started_at, " +
                "turn_count, tool_call_count, source_instance, " +
                "slug, git_branch, model, ended_at, session_tags " +
                "FROM sessions" + whereClause + " ORDER BY started_at ASC LIMIT 100")) {
            if (since != null) ps.setString(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode node = JSON.createObjectNode()
                            .put("session_id", rs.getString("session_id"))
                            .put("project_dir", rs.getString("project_dir"))
                            .put("first_message", rs.getString("first_message"))
                            .put("started_at", rs.getString("started_at"))
                            .put("turn_count", rs.getInt("turn_count"))
                            .put("tool_call_count", rs.getInt("tool_call_count"))
                            .put("source_instance", rs.getString("source_instance"))
                            .put("slug", rs.getString("slug"))
                            .put("git_branch", rs.getString("git_branch"))
                            .put("model", rs.getString("model"))
                            .put("ended_at", rs.getString("ended_at"))
                            .put("session_tags", rs.getString("session_tags"));
                    localSessions.add(node);
                }
            }
        }

        if (localSessions.isEmpty()) return;

        String payload = JSON.writeValueAsString(
                JSON.createObjectNode()
                        .put("sourceNode", localInstanceId)
                        .set("sessions", JSON.valueToTree(localSessions)));

        HttpResponse<String> response = postJson(baseUrl + "/ingest/sessions", payload);
        if (response != null && response.statusCode() == 200) {
            String lastTs = localSessions.get(localSessions.size() - 1).path("started_at").asText();
            cursorStore.updatePushSessionCursor(peerId, lastTs);
            LOG.fine("Pushed " + localSessions.size() + " sessions to " + peerId);
        }
    }

    private void pushEvents() throws IOException, InterruptedException, SQLException {
        String since = cursorStore.getLastPushEventTs(peerId);
        String whereClause = since != null ? " WHERE event_ts > ?" : "";

        List<JsonNode> localEvents = new ArrayList<>();
        try (PreparedStatement ps = db.getConnection().prepareStatement(
                "SELECT event_ts, tool, command, session_id, project_dir, " +
                "output_preview, source_instance, event_hash FROM tool_events" +
                whereClause + " ORDER BY event_ts ASC LIMIT 200")) {
            if (since != null) ps.setString(1, since);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode node = JSON.createObjectNode()
                            .put("event_ts", rs.getString("event_ts"))
                            .put("tool", rs.getString("tool"))
                            .put("command", rs.getString("command"))
                            .put("session_id", rs.getString("session_id"))
                            .put("project_dir", rs.getString("project_dir"))
                            .put("output_preview", rs.getString("output_preview"))
                            .put("source_instance", rs.getString("source_instance"))
                            .put("event_hash", rs.getString("event_hash"));
                    localEvents.add(node);
                }
            }
        }

        if (localEvents.isEmpty()) return;

        String payload = JSON.writeValueAsString(
                JSON.createObjectNode().set("events", JSON.valueToTree(localEvents)));

        HttpResponse<String> response = postJson(baseUrl + "/ingest/events", payload);
        if (response != null && response.statusCode() == 200) {
            String lastTs = localEvents.get(localEvents.size() - 1).path("event_ts").asText();
            cursorStore.updatePushEventCursor(peerId, lastTs);
            LOG.fine("Pushed " + localEvents.size() + " events to " + peerId);
        }
    }

    // --- HTTP helpers ---

    private JsonNode fetchJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOG.warning("Peer API returned " + response.statusCode() + " for " + url);
            return null;
        }
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> postJson(String url, String body)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            LOG.warning("Peer ingest returned " + response.statusCode() + " for " + url);
            return null;
        }
        return response;
    }

    // --- hashing ---

    /**
     * Compute a SHA-256 hash for event deduplication.
     * Public so IngestHandler can reuse it.
     */
    public static String computeEventHash(String timestamp, String tool, String command, String sessionId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((timestamp + "|" + tool + "|" + command + "|" + sessionId)
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
