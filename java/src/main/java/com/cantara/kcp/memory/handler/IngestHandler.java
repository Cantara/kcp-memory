package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.peer.PeerSyncService;
import com.cantara.kcp.memory.server.EventBroadcaster;
import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Logger;

/**
 * POST /ingest/sessions and POST /ingest/events — accept data pushed from peers.
 *
 * <p>This is the counterpart to PeerSyncService's push phase. It runs on the
 * internal API (localhost:7735) only, never on the external mobile API.
 *
 * <p>Handles both session and event ingestion based on path:
 * <ul>
 *   <li>{@code /ingest/sessions} — accepts a JSON array of sessions</li>
 *   <li>{@code /ingest/events} — accepts a JSON array of tool events</li>
 * </ul>
 *
 * <p>All records use INSERT OR IGNORE — duplicates are silently skipped.
 * Events use {@code event_hash} for deduplication across any sync path.
 */
public class IngestHandler extends BaseHandler {

    private static final Logger LOG = Logger.getLogger(IngestHandler.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final MemoryDatabase db;
    private EventBroadcaster broadcaster;

    public IngestHandler(MemoryDatabase db) {
        this.db = db;
    }

    /**
     * Set an optional EventBroadcaster to fan-out ingested events to WebSocket clients.
     * If not set, ingested events are still stored but not broadcast.
     */
    public void setBroadcaster(EventBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }

        String path = ex.getRequestURI().getPath();
        JsonNode body;
        try {
            body = JSON.readTree(ex.getRequestBody());
        } catch (Exception e) {
            sendError(ex, 400, "Invalid JSON");
            return;
        }

        try {
            if (path.endsWith("/sessions")) {
                int count = ingestSessions(body);
                sendJson(ex, 200, Map.of("ingested", count, "type", "sessions"));
            } else if (path.endsWith("/events")) {
                int count = ingestEvents(body);
                sendJson(ex, 200, Map.of("ingested", count, "type", "events"));
            } else {
                sendError(ex, 404, "Use /ingest/sessions or /ingest/events");
            }
        } catch (SQLException e) {
            LOG.warning("Ingest failed: " + e.getMessage());
            sendError(ex, 500, "Ingest failed: " + e.getMessage());
        }
    }

    private int ingestSessions(JsonNode body) throws SQLException {
        JsonNode sessions = body.get("sessions");
        if (sessions == null || !sessions.isArray()) return 0;

        // Sender identifies itself so we can remap "local" to the real peerId
        String senderPeerId = body.path("sourceNode").asText(null);

        int count = 0;
        for (JsonNode session : sessions) {
            String sessionId = session.path("session_id").asText(null);
            String projectDir = session.path("project_dir").asText("");
            String firstMessage = session.path("first_message").asText("");
            String startedAt = session.path("started_at").asText(null);
            String source = session.path("source_instance").asText("unknown");
            if ("local".equals(source) && senderPeerId != null) source = senderPeerId;
            int turnCount = session.path("turn_count").asInt(0);
            int toolCallCount = session.path("tool_call_count").asInt(0);
            String slug = session.path("slug").asText(null);
            String gitBranch = session.path("git_branch").asText(null);
            String model = session.path("model").asText(null);
            String endedAt = session.path("ended_at").asText(null);

            if (sessionId == null || startedAt == null) continue;

            try (PreparedStatement ps = db.getConnection().prepareStatement("""
                    INSERT INTO sessions
                        (session_id, project_dir, first_message, started_at,
                         turn_count, tool_call_count, scanned_at, source_instance,
                         slug, git_branch, model, ended_at)
                    VALUES (?, ?, ?, ?, ?, ?, datetime('now'), ?, ?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET
                        turn_count = MAX(turn_count, excluded.turn_count),
                        tool_call_count = MAX(tool_call_count, excluded.tool_call_count),
                        slug = COALESCE(excluded.slug, slug),
                        git_branch = COALESCE(excluded.git_branch, git_branch),
                        model = COALESCE(excluded.model, model),
                        ended_at = COALESCE(excluded.ended_at, ended_at),
                        source_instance = CASE WHEN source_instance = 'local' THEN excluded.source_instance ELSE source_instance END
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
                count += ps.executeUpdate();
            }
        }
        LOG.fine("Ingested " + count + " sessions from peer push");
        return count;
    }

    private int ingestEvents(JsonNode body) throws SQLException {
        JsonNode events = body.get("events");
        if (events == null || !events.isArray()) return 0;

        int count = 0;
        for (JsonNode event : events) {
            String eventTs = event.path("event_ts").asText(null);
            String tool = event.path("tool").asText("Bash");
            String command = event.path("command").asText("");
            String sessionId = event.path("session_id").asText("");
            String projectDir = event.path("project_dir").asText("");
            String outputPreview = event.path("output_preview").asText(null);
            String source = event.path("source_instance").asText("unknown");

            String hash = event.path("event_hash").asText(null);
            if (hash == null) {
                hash = PeerSyncService.computeEventHash(eventTs, tool, command, sessionId);
            }

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
                int inserted = ps.executeUpdate();
                count += inserted;

                // Broadcast to WebSocket subscribers if event was actually inserted
                if (inserted > 0 && broadcaster != null) {
                    try {
                        String broadcastJson = JSON.writeValueAsString(Map.of(
                                "type", "tool_event",
                                "peerId", source,
                                "tool", tool,
                                "command", command,
                                "timestamp", eventTs
                        ));
                        broadcaster.broadcast(broadcastJson);
                    } catch (Exception e) {
                        LOG.fine("Broadcast failed for event: " + e.getMessage());
                    }
                }
            }
        }
        LOG.fine("Ingested " + count + " events from peer push");
        return count;
    }
}
