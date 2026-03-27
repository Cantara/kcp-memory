package com.cantara.kcp.memory.mcp;

import com.cantara.kcp.memory.model.AgentSession;
import com.cantara.kcp.memory.model.ManifestQualityRecord;
import com.cantara.kcp.memory.model.ManifestVersionRecord;
import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.model.Session;
import com.cantara.kcp.memory.model.ToolEvent;
import com.cantara.kcp.memory.scanner.AgentSessionScanner;
import com.cantara.kcp.memory.scanner.EventLogScanner;
import com.cantara.kcp.memory.scanner.SessionScanner;
import com.cantara.kcp.memory.store.AgentSessionStore;
import com.cantara.kcp.memory.store.EventStore;
import com.cantara.kcp.memory.store.ManifestQualityStore;
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
 * Tools exposed (8):
 * <ul>
 *   <li>kcp_memory_search          — FTS5 search over session transcripts</li>
 *   <li>kcp_memory_events_search   — FTS5 search over tool-call events (requires kcp-commands v0.9.0)</li>
 *   <li>kcp_memory_list            — list recent sessions, optionally by project</li>
 *   <li>kcp_memory_stats           — aggregate statistics</li>
 *   <li>kcp_memory_session_detail  — full content of a specific session: user messages, files, tools (v0.4.0)</li>
 *   <li>kcp_memory_project_context — auto-detect project from PWD, return last 5 sessions + 20 events (v0.4.0)</li>
 *   <li>kcp_memory_subagent_search — FTS5 search within subagent transcripts (v0.5.0)</li>
 *   <li>kcp_memory_session_tree    — parent session + all child agents as a tree (v0.5.0)</li>
 *   <li>kcp_memory_analyze        — manifest quality metrics: retry/help/error rates per manifest key (v0.16.0)</li>
 * </ul>
 */
public class McpServer {

    private static final Logger LOG              = Logger.getLogger(McpServer.class.getName());
    private static final String PROTOCOL_VERSION = "2024-11-05";
    public  static final String SERVER_VERSION   = "0.21.0";

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
        new AgentSessionScanner(db).scan(false);

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

        tools.add(tool(
                "kcp_memory_session_detail",
                "Retrieve full content of a specific session by session ID — user messages, files " +
                "touched, and tools used. Use after kcp_memory_search to read what actually happened " +
                "in a session found by search. Session IDs are returned in search and list results.",
                schema()
                        .required("session_id", "string", "Session ID from a kcp_memory_search or kcp_memory_list result")
        ));

        tools.add(tool(
                "kcp_memory_project_context",
                "Retrieve recent activity for the current project directory. Returns recent sessions " +
                "and tool-call events for this project. Call this at the start of a session to " +
                "immediately know what was done here last time — no query needed.",
                schema()
                        .optional("project",       "string",  "Project directory path. Defaults to the current working directory (PWD env var).")
                        .optional("session_limit", "integer", "Max sessions to return (default 5).")
                        .optional("event_limit",   "integer", "Max tool-call events to return (default 20).")
        ));

        tools.add(tool(
                "kcp_memory_subagent_search",
                "Search within subagent (Task tool) transcripts from past Claude Code sessions. " +
                "Subagents contain the detailed reasoning, discoveries, and tool usage from delegated " +
                "tasks. Use this to find architectural discoveries, rejected approaches, and " +
                "cross-repository relationships found by past agents — e.g. 'CatalystOne events', " +
                "'Flyway migration', 'factory adapter'. Added in v0.5.0.",
                schema()
                        .required("query",             "string",  "Search terms to find in subagent transcripts")
                        .optional("parent_session_id", "string",  "Limit results to agents from a specific parent session")
                        .optional("limit",             "integer", "Max results (default 10)")
        ));

        tools.add(tool(
                "kcp_memory_session_tree",
                "Show a session and all its child subagents as a tree. Reveals which agents were " +
                "spawned during a session, what each investigated, and how many tool calls they made. " +
                "Use after kcp_memory_search to understand a session's full scope including delegated work. " +
                "Added in v0.5.0.",
                schema()
                        .required("session_id", "string", "Parent session ID from kcp_memory_search or kcp_memory_list")
        ));

        tools.add(tool(
                "kcp_memory_analyze",
                "Analyze manifest quality — surface which kcp-commands manifests correlate with retries " +
                "(same command run again within 90s), help-followups (agent ran --help within 5min), and " +
                "errors. Use this to find which manifests most need improvement. " +
                "Set by_version=true to compare quality before and after a manifest was changed " +
                "(requires kcp-commands v0.16.0). Added in v0.16.0.",
                schema()
                        .optional("since_days",  "integer", "Only consider events from the last N days (default 30)")
                        .optional("min_calls",   "integer", "Exclude manifests with fewer than N calls (default 5)")
                        .optional("top",         "integer", "Return at most N results (default 20)")
                        .optional("by_version",  "boolean", "Group by manifest content hash to compare before/after improvements")
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
                case "kcp_memory_search"          -> toolSearch(args);
                case "kcp_memory_events_search"   -> toolEventsSearch(args);
                case "kcp_memory_list"            -> toolList(args);
                case "kcp_memory_stats"           -> toolStats();
                case "kcp_memory_session_detail"  -> toolSessionDetail(args);
                case "kcp_memory_project_context" -> toolProjectContext(args);
                case "kcp_memory_subagent_search" -> toolSubagentSearch(args);
                case "kcp_memory_session_tree"    -> toolSessionTree(args);
                case "kcp_memory_analyze"         -> toolAnalyze(args);
                default                            -> "Unknown tool: " + name;
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

        UsageLogger.logSearch(query, results.size());

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

        UsageLogger.logSearch(query, results.size());

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

    private String toolSessionDetail(JsonNode args) throws Exception {
        String sessionId = args.path("session_id").asText("").strip();
        if (sessionId.isEmpty()) return "Error: session_id is required";

        Session s = new SessionStore(db).getById(sessionId);
        UsageLogger.logGet(sessionId);
        if (s == null) return "Session not found: " + sessionId;

        StringBuilder sb = new StringBuilder();
        sb.append("Session: ").append(s.getSessionId()).append("\n");
        sb.append("Project: ").append(s.getProjectDir()).append("\n");
        if (s.getGitBranch() != null) sb.append("Branch:  ").append(s.getGitBranch()).append("\n");
        if (s.getModel()     != null) sb.append("Model:   ").append(s.getModel()).append("\n");
        sb.append("Date:    ").append(s.getStartedAt() != null ? s.getStartedAt().substring(0, 10) : "?").append("\n");
        sb.append("Turns:   ").append(s.getTurnCount())
          .append("  Tool calls: ").append(s.getToolCallCount()).append("\n");

        if (s.getToolNames() != null && !s.getToolNames().isEmpty()) {
            sb.append("\nTools used: ").append(String.join(", ", s.getToolNames())).append("\n");
        }

        if (s.getFiles() != null && !s.getFiles().isEmpty()) {
            sb.append("\nFiles touched (").append(s.getFiles().size()).append("):\n");
            s.getFiles().stream().limit(30).forEach(f -> sb.append("  ").append(f).append("\n"));
            if (s.getFiles().size() > 30)
                sb.append("  … and ").append(s.getFiles().size() - 30).append(" more\n");
        }

        if (s.getAllUserText() != null && !s.getAllUserText().isBlank()) {
            sb.append("\nUser messages:\n");
            String text = s.getAllUserText();
            if (text.length() > 4000) {
                sb.append(text, 0, 4000).append("\n… [truncated — ").append(text.length() - 4000).append(" more chars]\n");
            } else {
                sb.append(text).append("\n");
            }
        }

        return sb.toString();
    }

    private String toolProjectContext(JsonNode args) throws Exception {
        // Resolve project directory: explicit arg → PWD env → fallback message
        String project = args.path("project").asText("").strip();
        if (project.isEmpty()) project = System.getenv("PWD");
        if (project == null || project.isBlank())
            return "Could not determine project directory. Pass 'project' argument explicitly.";

        // Ingest latest events before querying
        new EventLogScanner(db).scan();

        int sessionLimit = args.path("session_limit").asInt(5);
        int eventLimit   = args.path("event_limit").asInt(20);

        SessionStore      sessionStore = new SessionStore(db);
        EventStore        eventStore   = new EventStore(db);

        List<SearchResult> sessions = sessionStore.list(project, sessionLimit);
        List<ToolEvent>    events   = eventStore.list(project, eventLimit);

        if (sessions.isEmpty() && events.isEmpty())
            return "No history found for: " + project +
                   "\n\nThis project has not been indexed yet. To fix:" +
                   "\n  1. Run: kcp-memory scan" +
                   "\n  2. Or add a git post-commit hook (see README) for automatic indexing." +
                   "\n\n{\"sessions\": [], \"events\": [], \"hint\": \"run kcp-memory scan\"}";

        StringBuilder sb = new StringBuilder();
        sb.append("Project context: ").append(project).append("\n\n");

        if (!sessions.isEmpty()) {
            sb.append("Recent sessions (").append(sessions.size()).append("):\n\n");
            for (SearchResult r : sessions) sb.append(formatSession(r));
        }

        if (!events.isEmpty()) {
            sb.append("Recent tool-call events (").append(events.size()).append("):\n\n");
            for (ToolEvent e : events) sb.append(formatEvent(e));
        }

        return sb.toString();
    }

    private String toolSubagentSearch(JsonNode args) throws Exception {
        String query           = args.path("query").asText("").strip();
        String parentSessionId = args.path("parent_session_id").asText(null);
        if (parentSessionId != null && parentSessionId.isBlank()) parentSessionId = null;
        int    limit           = args.path("limit").asInt(10);
        if (query.isEmpty()) return "Error: query is required";

        // Refresh agent index before searching
        new AgentSessionScanner(db).scan(false);

        AgentSessionStore    store   = new AgentSessionStore(db);
        List<AgentSession>   results = store.search(query, parentSessionId, limit);

        if (results.isEmpty()) return "No subagent sessions found for: " + query;

        StringBuilder sb = new StringBuilder();
        sb.append(results.size()).append(" subagent session(s) for \"").append(query).append("\":\n\n");
        for (AgentSession a : results) sb.append(formatAgent(a));
        return sb.toString();
    }

    private String toolSessionTree(JsonNode args) throws Exception {
        String sessionId = args.path("session_id").asText("").strip();
        if (sessionId.isEmpty()) return "Error: session_id is required";

        // Refresh agent index
        new AgentSessionScanner(db).scan(false);

        Session            parent = new SessionStore(db).getById(sessionId);
        List<AgentSession> agents = new AgentSessionStore(db).listByParent(sessionId, 100);

        StringBuilder sb = new StringBuilder();

        // Parent session header
        if (parent != null) {
            String date = parent.getStartedAt() != null ? parent.getStartedAt().substring(0, 10) : "?";
            sb.append("Session: ").append(parent.getSessionId()).append("\n");
            sb.append("Date:    ").append(date).append("\n");
            sb.append("Project: ").append(parent.getProjectDir()).append("\n");
            sb.append("Turns:   ").append(parent.getTurnCount())
              .append("  Tool calls: ").append(parent.getToolCallCount()).append("\n");
            if (parent.getFirstMessage() != null) {
                String msg = parent.getFirstMessage();
                if (msg.length() > 100) msg = msg.substring(0, 100) + "…";
                sb.append("Task:    \"").append(msg).append("\"\n");
            }
        } else {
            sb.append("Session: ").append(sessionId).append(" (not indexed as main session)\n");
        }

        // Child agents
        sb.append("\nSubagents (").append(agents.size()).append("):\n");
        if (agents.isEmpty()) {
            sb.append("  (none indexed — run kcp-memory scan to index subagents)\n");
        } else {
            for (AgentSession a : agents) {
                String agentShort = a.getAgentId().substring(0, Math.min(8, a.getAgentId().length()));
                sb.append("  ├─ ").append(agentShort);
                if (a.getCwd() != null) sb.append("  [").append(a.getCwd()).append("]");
                sb.append("\n");
                sb.append("  │  turns=").append(a.getTurnCount())
                  .append("  tools=").append(a.getToolCallCount());
                if (a.getToolNames() != null && !a.getToolNames().isEmpty()) {
                    String tools = String.join(", ", a.getToolNames().stream().limit(5).toList());
                    sb.append("  (").append(tools);
                    if (a.getToolNames().size() > 5) sb.append(", …");
                    sb.append(")");
                }
                sb.append("\n");
                if (a.getFirstMessage() != null) {
                    String msg = a.getFirstMessage();
                    if (msg.length() > 100) msg = msg.substring(0, 100) + "…";
                    sb.append("  │  \"").append(msg).append("\"\n");
                }
                sb.append("  │\n");
            }
        }
        return sb.toString();
    }

    private String toolAnalyze(JsonNode args) throws Exception {
        int sinceDays = args.path("since_days").asInt(30);
        int minCalls  = args.path("min_calls").asInt(5);
        int top       = args.path("top").asInt(20);
        boolean byVersion = args.path("by_version").asBoolean(false);

        new EventLogScanner(db).scan();
        ManifestQualityStore store = new ManifestQualityStore(db);
        int  totalManifests = store.countManifests();
        long totalCalls     = store.countManifestCalls();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Manifest quality — %d manifests, %,d total calls (last %d days, min %d calls)%n%n",
                totalManifests, totalCalls, sinceDays, minCalls));

        if (byVersion) {
            List<ManifestVersionRecord> records = store.analyzeByVersion(sinceDays, minCalls);
            if (records.isEmpty()) {
                sb.append("No data. Run kcp-commands v0.16.0+ to generate manifest_version events.");
                return sb.toString();
            }
            String today      = java.time.LocalDate.now().toString();
            String currentKey = null;
            double prevScore  = Double.NaN;
            for (ManifestVersionRecord r : records) {
                if (!r.manifestKey().equals(currentKey)) {
                    if (currentKey != null) sb.append("\n");
                    sb.append(r.manifestKey()).append(":\n");
                    currentKey = r.manifestKey();
                    prevScore  = Double.NaN;
                }
                String dateTo = r.lastSeen().equals(today) ? "present" : r.lastSeen();
                String diff   = Double.isNaN(prevScore) ? "  ← before"
                        : String.format("  %s %.2f", (r.qualityScore() - prevScore) <= 0 ? "↓" : "↑",
                                Math.abs(r.qualityScore() - prevScore));
                sb.append(String.format("  [%s]  %s → %-10s  calls=%d  retry=%.0f%%  score=%.2f%s%n",
                        r.manifestVersion(), r.firstSeen(), dateTo,
                        r.totalCalls(), r.retryRate() * 100, r.qualityScore(), diff));
                prevScore = r.qualityScore();
            }
        } else {
            List<ManifestQualityRecord> records = store.analyze(sinceDays, minCalls, top);
            if (records.isEmpty()) {
                sb.append("No manifests with enough data. Needs kcp-commands v0.9.0+ generating events.");
                return sb.toString();
            }
            sb.append(String.format("  %-25s %5s   %7s  %13s  %6s   %5s%n",
                    "MANIFEST KEY", "CALLS", "RETRIES", "HELP-FOLLOWUP", "ERRORS", "SCORE"));
            sb.append("  ").append("─".repeat(72)).append("\n");
            for (ManifestQualityRecord r : records) {
                String flag = r.qualityScore() >= 0.20 ? "  ← needs attention"
                            : r.qualityScore() <= 0.05 ? "  ok" : "";
                sb.append(String.format("  %-25s %5d   %5.0f%%  %11.0f%%  %4.0f%%   %.2f%s%n",
                        r.manifestKey().length() <= 25 ? r.manifestKey()
                                : r.manifestKey().substring(0, 24) + "…",
                        r.totalCalls(),
                        r.retryRate() * 100, r.helpFollowupRate() * 100,
                        r.errorRate() * 100, r.qualityScore(), flag));
            }
            long needsAttention = records.stream().filter(r -> r.qualityScore() >= 0.20).count();
            if (needsAttention > 0) {
                sb.append(String.format("%n  %d manifest(s) need attention. Improve at ~/.kcp/commands/<key>.yaml%n", needsAttention));
            }
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

    private String formatAgent(AgentSession a) {
        StringBuilder sb = new StringBuilder();
        String agentShort = a.getAgentId().substring(0, Math.min(8, a.getAgentId().length()));
        String parentShort = a.getParentSessionId().substring(0, Math.min(8, a.getParentSessionId().length()));
        sb.append(agentShort).append("  [sub of ").append(parentShort).append("]");
        if (a.getCwd() != null) sb.append("  ").append(a.getCwd());
        sb.append("\n");
        sb.append("turns=").append(a.getTurnCount())
          .append("  tools=").append(a.getToolCallCount()).append("\n");
        if (a.getFirstMessage() != null) {
            String msg = a.getFirstMessage();
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
        sb.append("$ ").append(cmd).append("\n");
        if (e.outputPreview() != null) {
            sb.append("→ ").append(e.outputPreview()).append("\n");
        }
        sb.append("\n");
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
