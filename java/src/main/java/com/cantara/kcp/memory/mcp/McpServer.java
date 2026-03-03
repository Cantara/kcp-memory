package com.cantara.kcp.memory.mcp;

import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.model.ToolEvent;
import com.cantara.kcp.memory.scanner.EventLogScanner;
import com.cantara.kcp.memory.scanner.SessionScanner;
import com.cantara.kcp.memory.store.EventStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.cantara.kcp.memory.store.ToolUsageStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.List;
import java.util.logging.Logger;

/**
 * MCP stdio server — exposes kcp-memory as MCP tools for Claude Code.
 * <p>
 * Transport: stdio (newline-delimited JSON-RPC 2.0).
 * IMPORTANT: stdout is the protocol channel — all logging goes to stderr.
 * <p>
 * Register in ~/.claude/settings.json:
 * <pre>
 * {
 *   "mcpServers": {
 *     "kcp-memory": {
 *       "command": "java",
 *       "args": ["-jar", "/home/totto/.kcp/kcp-memory-daemon.jar", "mcp"]
 *     }
 *   }
 * }
 * </pre>
 * <p>
 * Tools exposed:
 * <ul>
 *   <li>kcp_memory_search        — FTS5 search over session transcripts</li>
 *   <li>kcp_memory_events_search — FTS5 search over tool-call events (requires kcp-commands v0.9.0)</li>
 *   <li>kcp_memory_list          — list recent sessions, optionally by project</li>
 *   <li>kcp_memory_stats         — aggregate statistics</li>
 * </ul>
 */
public class McpServer {

    private static final Logger LOG              = Logger.getLogger(McpServer.class.getName());
    private static final String PROTOCOL_VERSION = "2024-11-05";
    public  static final String SERVER_VERSION   = "0.3.0";

    private final ObjectMapper   mapper = new ObjectMapper();
    private final MemoryDatabase db;

    public McpServer(MemoryDatabase db) {
        this.db = db;
    }

    /** Block on stdin until EOF, processing one JSON-RPC message per line. */
    public void run() throws Exception {
        // Initial scan so tools return fresh data immediately
        new SessionScanner(db).scan(false);
        new EventLogScanner(db).scan();

        // stdout = protocol; auto-flush so responses are sent immediately
        PrintWriter    out = new PrintWriter(System.out, true);
        BufferedReader in  = new BufferedReader(new InputStreamReader(System.in));

        String line;
        while ((line = in.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty()) continue;
            try {
                String response = handle(line);
                if (response != null) {
                    out.println(response);
                }
            } catch (Exception e) {
                System.err.println("[kcp-memory mcp] error: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // Request dispatch
    // ------------------------------------------------------------------

    private String handle(String line) throws Exception {
        JsonNode req    = mapper.readTree(line);
        String   method = req.path("method").asText("");
        JsonNode id     = req.get("id");  // null for notifications

        return switch (method) {
            case "initialize"                -> respond(id, buildInitializeResult());
            case "notifications/initialized" -> null;  // notification — no response
            case "ping"                      -> respond(id, mapper.createObjectNode());
            case "tools/list"                -> respond(id, buildToolsList());
            case "tools/call"                -> respond(id, callTool(req.path("params")));
            default                          -> id != null
                    ? respondError(id, -32601, "Method not found: " + method)
                    : null;
        };
    }

    // ------------------------------------------------------------------
    // initialize
    // ------------------------------------------------------------------

    private ObjectNode buildInitializeResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name",    "kcp-memory");
        info.put("version", SERVER_VERSION);
        return result;
    }

    // ------------------------------------------------------------------
    // tools/list
    // ------------------------------------------------------------------

    private ObjectNode buildToolsList() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode  tools  = result.putArray("tools");

        tools.add(tool(
                "kcp_memory_search",
                "Search past Claude Code sessions by keyword. Returns sessions where the topic " +
                "was discussed, code was written, or problems were solved. Use this to recall " +
                "what was done in previous sessions — e.g. 'OAuth implementation', " +
                "'database migration flyway', 'kubernetes deploy'.",
                schema()
                        .required("query", "string",  "Search terms — topic, technology, filename, or problem")
                        .optional("limit", "integer", "Max results (default 10)")
        ));

        tools.add(tool(
                "kcp_memory_events_search",
                "Search past tool-call events — find which exact commands Claude ran across all projects. " +
                "Requires kcp-commands v0.9.0 to be generating ~/.kcp/events.jsonl. " +
                "Use this to recall specific commands like 'kubectl apply', 'mvn package', 'flyway migrate'.",
                schema()
                        .required("query", "string",  "Command or keyword (e.g. 'kubectl apply', 'docker build')")
                        .optional("limit", "integer", "Max results (default 20)")
        ));

        tools.add(tool(
                "kcp_memory_list",
                "List recent Claude Code sessions, optionally filtered to a specific project directory. " +
                "Shows date, turn count, tool call count, and the first user message per session.",
                schema()
                        .optional("project", "string",  "Filter by project directory path (e.g. /src/myapp)")
                        .optional("limit",   "integer", "Max results (default 20)")
        ));

        tools.add(tool(
                "kcp_memory_stats",
                "Show aggregate statistics across all indexed Claude Code sessions: total sessions, " +
                "turns, tool calls, date range, and top tools used.",
                schema().noRequired()
        ));

        return result;
    }

    private ObjectNode tool(String name, String description, SchemaBuilder schema) {
        ObjectNode t = mapper.createObjectNode();
        t.put("name",        name);
        t.put("description", description);
        t.set("inputSchema", schema.build());
        return t;
    }

    // ------------------------------------------------------------------
    // tools/call
    // ------------------------------------------------------------------

    private ObjectNode callTool(JsonNode params) {
        String   name = params.path("name").asText("");
        JsonNode args = params.path("arguments");

        String text;
        boolean isError = false;
        try {
            text = switch (name) {
                case "kcp_memory_search"        -> toolSearch(args);
                case "kcp_memory_events_search" -> toolEventsSearch(args);
                case "kcp_memory_list"          -> toolList(args);
                case "kcp_memory_stats"         -> toolStats();
                default                          -> "Unknown tool: " + name;
            };
        } catch (Exception e) {
            text    = "Error: " + e.getMessage();
            isError = true;
            System.err.println("[kcp-memory mcp] tool error in " + name + ": " + e.getMessage());
        }

        ObjectNode result  = mapper.createObjectNode();
        ArrayNode  content = result.putArray("content");
        ObjectNode item    = content.addObject();
        item.put("type", "text");
        item.put("text", text);
        if (isError) result.put("isError", true);
        return result;
    }

    private String toolSearch(JsonNode args) throws Exception {
        String query = args.path("query").asText("").strip();
        int    limit = args.path("limit").asInt(10);
        if (query.isEmpty()) return "Error: query is required";

        SessionStore       store   = new SessionStore(db);
        List<SearchResult> results = store.search(query, limit);

        if (results.isEmpty()) return "No sessions found for: " + query;

        StringBuilder sb = new StringBuilder();
        sb.append(results.size()).append(" session(s) for \"").append(query).append("\":\n\n");
        for (SearchResult r : results) sb.append(formatSession(r));
        return sb.toString();
    }

    private String toolEventsSearch(JsonNode args) throws Exception {
        String query = args.path("query").asText("").strip();
        int    limit = args.path("limit").asInt(20);
        if (query.isEmpty()) return "Error: query is required";

        // Ingest any events written since last scan before searching
        new EventLogScanner(db).scan();

        EventStore       store   = new EventStore(db);
        List<ToolEvent>  results = store.search(query, limit);

        if (results.isEmpty())
            return "No events found for: " + query +
                   "\n(requires kcp-commands v0.9.0 writing ~/.kcp/events.jsonl)";

        StringBuilder sb = new StringBuilder();
        sb.append(results.size()).append(" event(s) for \"").append(query).append("\":\n\n");
        for (ToolEvent e : results) sb.append(formatEvent(e));
        return sb.toString();
    }

    private String toolList(JsonNode args) throws Exception {
        String project = args.path("project").asText(null);
        if (project != null && project.isBlank()) project = null;
        int limit = args.path("limit").asInt(20);

        SessionStore       store    = new SessionStore(db);
        List<SearchResult> sessions = store.list(project, limit);

        if (sessions.isEmpty()) return "No sessions indexed yet. Run: kcp-memory scan";

        StringBuilder sb = new StringBuilder();
        sb.append(sessions.size()).append(" session(s)");
        if (project != null) sb.append(" in ").append(project);
        sb.append(":\n\n");
        for (SearchResult r : sessions) sb.append(formatSession(r));
        return sb.toString();
    }

    private String toolStats() throws Exception {
        SessionStore.Stats stats    = new SessionStore(db).stats();
        var                topTools = new ToolUsageStore(db).topTools(10);

        StringBuilder sb = new StringBuilder();
        sb.append("kcp-memory statistics\n");
        sb.append("─────────────────────────────────\n");
        sb.append(String.format("Sessions:    %,d%n", stats.totalSessions()));
        sb.append(String.format("Turns:       %,d%n", stats.totalTurns()));
        sb.append(String.format("Tool calls:  %,d%n", stats.totalToolCalls()));
        sb.append(String.format("Oldest:      %s%n", stats.oldest() != null ? stats.oldest() : "—"));
        sb.append(String.format("Newest:      %s%n", stats.newest() != null ? stats.newest() : "—"));
        if (!topTools.isEmpty()) {
            sb.append("\nTop tools:\n");
            topTools.forEach(t -> sb.append(String.format("  %-25s %,d%n", t.toolName(), t.count())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Formatting
    // ------------------------------------------------------------------

    private String formatSession(SearchResult r) {
        StringBuilder sb = new StringBuilder();
        String date = r.getStartedAt() != null ? r.getStartedAt().substring(0, 10) : "?";
        sb.append(date).append("  ").append(r.getProjectDir()).append("\n");
        String sid = r.getSessionId();
        sb.append(sid.substring(0, Math.min(8, sid.length())))
          .append("  turns=").append(r.getTurnCount())
          .append("  tools=").append(r.getToolCallCount()).append("\n");
        if (r.getFirstMessage() != null) {
            String msg = r.getFirstMessage();
            if (msg.length() > 120) msg = msg.substring(0, 120) + "…";
            sb.append("\"").append(msg).append("\"\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private String formatEvent(ToolEvent e) {
        StringBuilder sb = new StringBuilder();
        String ts  = e.eventTs() != null ? e.eventTs().substring(0, 19).replace('T', ' ') : "?";
        sb.append(ts).append("  ").append(e.projectDir()).append("\n");
        String sid = e.sessionId();
        sb.append(sid.substring(0, Math.min(8, sid.length())))
          .append("  [").append(e.manifestKey() != null ? e.manifestKey() : "no-manifest").append("]\n");
        String cmd = e.command();
        if (cmd.length() > 120) cmd = cmd.substring(0, 120) + "…";
        sb.append("$ ").append(cmd).append("\n\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // JSON-RPC helpers
    // ------------------------------------------------------------------

    private String respond(JsonNode id, ObjectNode result) throws Exception {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null) resp.set("id", id);
        resp.set("result", result);
        return mapper.writeValueAsString(resp);
    }

    private String respondError(JsonNode id, int code, String message) throws Exception {
        ObjectNode resp  = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null) resp.set("id", id);
        ObjectNode error = resp.putObject("error");
        error.put("code",    code);
        error.put("message", message);
        return mapper.writeValueAsString(resp);
    }

    // ------------------------------------------------------------------
    // Schema builder
    // ------------------------------------------------------------------

    private SchemaBuilder schema() { return new SchemaBuilder(mapper); }

    private static class SchemaBuilder {
        private final ObjectMapper mapper;
        private final ObjectNode   schema;
        private final ObjectNode   properties;
        private final ArrayNode    required;

        SchemaBuilder(ObjectMapper mapper) {
            this.mapper     = mapper;
            this.schema     = mapper.createObjectNode();
            this.properties = schema.putObject("properties");
            this.required   = schema.putArray("required");
            schema.put("type", "object");
        }

        SchemaBuilder required(String name, String type, String description) {
            ObjectNode prop = properties.putObject(name);
            prop.put("type",        type);
            prop.put("description", description);
            required.add(name);
            return this;
        }

        SchemaBuilder optional(String name, String type, String description) {
            ObjectNode prop = properties.putObject(name);
            prop.put("type",        type);
            prop.put("description", description);
            return this;
        }

        SchemaBuilder noRequired() { return this; }

        ObjectNode build() { return schema; }
    }
}
