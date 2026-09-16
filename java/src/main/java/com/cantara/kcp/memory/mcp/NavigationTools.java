package com.cantara.kcp.memory.mcp;

import com.cantara.kcp.memory.model.AgentSession;
import com.cantara.kcp.memory.model.Decision;
import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.model.Session;
import com.cantara.kcp.memory.model.ToolEvent;
import com.cantara.kcp.memory.scanner.AgentSessionScanner;
import com.cantara.kcp.memory.store.AgentSessionStore;
import com.cantara.kcp.memory.store.DecisionStore;
import com.cantara.kcp.memory.store.EventStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.NavTrajectoryStore;
import com.cantara.kcp.memory.store.SessionStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Generic filesystem-shaped navigation over kcp-memory's data — backs the
 * {@code kcp_memory_ls}, {@code kcp_memory_tree}, {@code kcp_memory_read} and
 * {@code kcp_memory_nav_history} MCP tools (v0.38.0).
 *
 * <h2>Why this exists</h2>
 * kcp-memory's twelve purpose-specific tools (search, list, session_detail,
 * session_tree, decisions, …) each have their own bespoke query semantics an
 * agent has to learn per tool. This class ADDITIONALLY exposes the same
 * underlying data through a single, uniform verb set — {@code ls}/{@code
 * tree}/{@code read} — the shape every agent already knows from a real
 * filesystem. It does not replace or change the behavior of any existing
 * tool; it is a second, generic surface layered on top of the same stores.
 *
 * <h2>Virtual path scheme</h2>
 * <pre>
 * /                                     4 top-level directories
 * /sessions                             recent sessions (SessionStore.list)
 * /sessions/&lt;session-id-or-prefix&gt;      one session — directory if it has
 *                                        subagents, else a leaf; read() gives
 *                                        the same content as kcp_memory_session_detail
 * /sessions/&lt;session-id&gt;/&lt;agent-id&gt;      one subagent of that session (leaf)
 * /decisions                            recent decisions (DecisionStore.filter)
 * /decisions/&lt;decision-id&gt;              one decision (leaf)
 * /events                               recent tool-call events (EventStore.list)
 * /events/&lt;event-id&gt;                    one event, by row id (leaf)
 * /subagents                            all subagents across every parent session
 * /subagents/&lt;agent-id&gt;                 same leaf as /sessions/&lt;id&gt;/&lt;agent-id&gt;
 *                                        (looked up directly by agent id — a
 *                                        flat alias, not a duplicate implementation)
 * </pre>
 * Every entry returned by {@code ls}/{@code tree} is marked {@code directory}
 * (has children — browse further) or {@code file} (a leaf — read it). Nothing
 * in the current data model nests deeper than session → subagent, so no path
 * is ever more than 3 segments deep; {@code ls} on a leaf path behaves like
 * {@code ls} on a file in a real shell — it lists just that one entry instead
 * of erroring, which keeps the tool forgiving of an agent probing a path it
 * isn't sure is a directory yet.
 *
 * <h2>Reuse over duplication</h2>
 * Where an existing tool already answers the same question, this class calls
 * the exact same store methods rather than re-implementing the query:
 * <ul>
 *   <li>session detail  → {@link SessionStore#getByIdOrPrefix} +
 *       {@link McpServer#governanceDenialReason} (same gate {@code
 *       kcp_memory_session_detail} uses)</li>
 *   <li>session tree     → {@link SessionStore#getByIdOrPrefix} +
 *       {@link AgentSessionStore#listByParent} (same two calls {@code
 *       kcp_memory_session_tree}'s {@code toolSessionTree} makes — there is no
 *       deeper tree-walking algorithm to extract because subagents don't
 *       nest further in this schema)</li>
 * </ul>
 * Paths with no existing single-item tool to delegate to — a single event by
 * id, a single decision by id, a single subagent by id — are backed by new,
 * minimal {@code getById}-style methods added to the relevant store
 * ({@link EventStore#getById}, {@link DecisionStore#getById},
 * {@link AgentSessionStore#getById}), following the same pattern
 * {@link SessionStore#getById} already established.
 *
 * <h2>Retrieval-trajectory logging</h2>
 * Every {@code ls}/{@code tree}/{@code read} call appends one row to the
 * {@code nav_trajectory} table (schema: {@code db/V10__nav_trajectory.sql}) —
 * timestamp, tool, virtual path, an optional caller-supplied correlation id,
 * and a one-line result summary — via {@link #logTrajectory}. That table is
 * read back by {@code kcp_memory_nav_history}. Logging is deliberately
 * synchronous on the calling thread rather than fire-and-forget on a virtual
 * thread the way {@link UsageLogger} logs to its own separate {@code
 * usage.db}: the MCP stdio loop in {@link McpServer#run} processes exactly
 * one JSON-RPC line at a time, so the shared {@link MemoryDatabase}
 * connection is never touched concurrently — spawning an async writer against
 * that same shared connection would introduce a real concurrency hazard that
 * doesn't exist today, for no benefit (these are single-row inserts).
 * {@link #logTrajectory} still follows this codebase's established
 * log-and-swallow convention for non-critical side effects (mirroring
 * {@code UsageLogger}'s catch-and-report-to-stderr): any failure to write the
 * trajectory row is caught and printed to stderr, never thrown into the
 * ls/tree/read response the caller is waiting on.
 */
public class NavigationTools {

    /**
     * Hard cap on {@code kcp_memory_tree} recursion depth. Nothing in the
     * current data model nests more than 3 levels deep (root → category →
     * item → subagent), so 4 leaves one level of slack for a future category
     * gaining its own children without ever allowing a runaway full-database
     * dump if a caller passes an absurd depth.
     */
    public static final int MAX_TREE_DEPTH = 4;

    private static final int DEFAULT_TREE_DEPTH        = 2;
    private static final int DEFAULT_LS_LIMIT           = 20;
    private static final int DEFAULT_NAV_HISTORY_LIMIT  = 20;

    private final MemoryDatabase db;

    public NavigationTools(MemoryDatabase db) {
        this.db = db;
    }

    // ------------------------------------------------------------------
    // kcp_memory_ls
    // ------------------------------------------------------------------

    public String ls(JsonNode args) throws Exception {
        String path                = args.path("path").asText("/");
        int    limit                = args.path("limit").asInt(DEFAULT_LS_LIMIT);
        String callerSessionId      = optionalText(args, "session_id");
        List<String> segs           = segments(path);

        try {
            List<Entry> entries = lsEntries(segs, limit);
            String      text    = renderLs(displayPath(segs), entries);
            logTrajectory("ls", displayPath(segs), callerSessionId,
                    "returned " + entries.size() + " entr" + (entries.size() == 1 ? "y" : "ies"));
            return text;
        } catch (NavNotFoundException e) {
            logTrajectory("ls", displayPath(segs), callerSessionId, "not found: " + e.getMessage());
            return e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // kcp_memory_tree
    // ------------------------------------------------------------------

    public String tree(JsonNode args) throws Exception {
        String path             = args.path("path").asText("/");
        int    limit             = args.path("limit").asInt(DEFAULT_LS_LIMIT);
        int    requestedDepth    = args.path("depth").asInt(DEFAULT_TREE_DEPTH);
        int    depth             = Math.max(1, Math.min(requestedDepth, MAX_TREE_DEPTH));
        String callerSessionId   = optionalText(args, "session_id");
        List<String> segs        = segments(path);

        StringBuilder sb        = new StringBuilder();
        int[]         nodeCount = {0};
        sb.append(displayPath(segs)).append("\n");
        try {
            appendTree(segs, depth, 1, limit, sb, nodeCount);
        } catch (NavNotFoundException e) {
            logTrajectory("tree", displayPath(segs), callerSessionId, "not found: " + e.getMessage());
            return e.getMessage();
        }
        logTrajectory("tree", displayPath(segs), callerSessionId,
                "returned " + nodeCount[0] + " node(s), depth=" + depth);
        return sb.toString();
    }

    private void appendTree(List<String> segs, int maxDepth, int currentDepth, int limit,
                             StringBuilder sb, int[] nodeCount) throws Exception {
        List<Entry> entries = lsEntries(segs, limit);
        String      indent  = "  ".repeat(currentDepth);
        for (Entry e : entries) {
            nodeCount[0]++;
            sb.append(indent).append(e.kind().equals("directory") ? "d " : "- ")
              .append(e.path()).append("  ").append(e.summary()).append("\n");
            if (e.kind().equals("directory") && currentDepth < maxDepth) {
                appendTree(segments(e.path()), maxDepth, currentDepth + 1, limit, sb, nodeCount);
            }
        }
    }

    // ------------------------------------------------------------------
    // kcp_memory_read
    // ------------------------------------------------------------------

    public String read(JsonNode args) throws Exception {
        String path           = args.path("path").asText("").strip();
        String callerSessionId = optionalText(args, "session_id");
        if (path.isEmpty()) return "Error: path is required";

        List<String> segs = segments(path);
        try {
            String text = doRead(segs);
            logTrajectory("read", displayPath(segs), callerSessionId,
                    "returned " + humanSize(text.length()) + " from /" + segs.get(0));
            return text;
        } catch (NavNotFoundException e) {
            logTrajectory("read", displayPath(segs), callerSessionId, "not found: " + e.getMessage());
            return e.getMessage();
        }
    }

    private String doRead(List<String> segs) throws Exception {
        if (segs.isEmpty()) throw new NavNotFoundException("/ is a directory. Use kcp_memory_ls.");
        return switch (segs.get(0)) {
            case "sessions"  -> readSessionPath(segs);
            case "decisions" -> readDecision(segs);
            case "events"    -> readEvent(segs);
            case "subagents" -> readSubagent(segs);
            default -> throw new NavNotFoundException(
                    "No such path: " + displayPath(segs) +
                    " (valid top-level paths: /sessions, /decisions, /events, /subagents)");
        };
    }

    private String readSessionPath(List<String> segs) throws Exception {
        if (segs.size() < 2) throw new NavNotFoundException("/sessions is a directory. Use kcp_memory_ls.");

        SessionStore sessionStore = new SessionStore(db);
        Session      parent       = sessionStore.getByIdOrPrefix(segs.get(1));
        if (parent == null) throw new NavNotFoundException("No such session: " + segs.get(1));

        String denied = McpServer.governanceDenialReason(sessionStore, parent.getSessionId());
        if (denied != null)
            throw new NavNotFoundException("Session " + parent.getSessionId() + " is not accessible: " + denied);

        if (segs.size() == 2) return renderSessionDetail(parent);

        String      agentId = segs.get(2);
        AgentSession agent  = new AgentSessionStore(db).getById(agentId);
        if (agent == null || !parent.getSessionId().equals(agent.getParentSessionId()))
            throw new NavNotFoundException("No such subagent: " + agentId + " under session " + parent.getSessionId());
        return renderAgentDetail(agent);
    }

    private String readDecision(List<String> segs) throws SQLException {
        if (segs.size() < 2) throw new NavNotFoundException("/decisions is a directory. Use kcp_memory_ls.");
        Decision d = new DecisionStore(db).getById(segs.get(1));
        if (d == null) throw new NavNotFoundException("No such decision: " + segs.get(1));
        return renderDecisionDetail(d);
    }

    private String readEvent(List<String> segs) throws SQLException {
        if (segs.size() < 2) throw new NavNotFoundException("/events is a directory. Use kcp_memory_ls.");
        long      id = parseEventId(segs.get(1));
        ToolEvent e  = new EventStore(db).getById(id);
        if (e == null) throw new NavNotFoundException("No such event: " + segs.get(1));
        return renderEventDetail(e);
    }

    private String readSubagent(List<String> segs) throws SQLException {
        if (segs.size() < 2) throw new NavNotFoundException("/subagents is a directory. Use kcp_memory_ls.");
        AgentSession a = new AgentSessionStore(db).getById(segs.get(1));
        if (a == null) throw new NavNotFoundException("No such subagent: " + segs.get(1));
        return renderAgentDetail(a);
    }

    // ------------------------------------------------------------------
    // kcp_memory_nav_history
    // ------------------------------------------------------------------

    public String navHistory(JsonNode args) throws SQLException {
        int    limit     = args.path("limit").asInt(DEFAULT_NAV_HISTORY_LIMIT);
        String sessionId = optionalText(args, "session_id");
        String since     = optionalText(args, "since");

        List<NavTrajectoryStore.Entry> entries = new NavTrajectoryStore(db).recent(limit, sessionId, since);
        if (entries.isEmpty()) return "No navigation history recorded yet.";

        StringBuilder sb = new StringBuilder();
        sb.append(entries.size()).append(" navigation call(s)");
        if (sessionId != null) sb.append(" for session ").append(sessionId);
        sb.append(":\n\n");
        for (NavTrajectoryStore.Entry e : entries) {
            sb.append(e.ts()).append("  [").append(e.tool()).append("]  ").append(e.path()).append("\n");
            sb.append("    ").append(e.summary()).append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Directory listing (shared by ls and tree)
    // ------------------------------------------------------------------

    private List<Entry> lsEntries(List<String> segs, int limit) throws Exception {
        if (segs.isEmpty()) {
            return List.of(
                    new Entry("sessions",  "/sessions",  "directory", "recent Claude Code sessions"),
                    new Entry("decisions", "/decisions", "directory", "architectural decisions, constraints, anti-patterns"),
                    new Entry("events",    "/events",    "directory", "tool-call events (requires kcp-commands)"),
                    new Entry("subagents", "/subagents", "directory", "subagent (Task tool) sessions across all parents")
            );
        }
        return switch (segs.get(0)) {
            case "sessions"  -> lsSessions(segs, limit);
            case "decisions" -> lsDecisions(segs, limit);
            case "events"    -> lsEvents(segs, limit);
            case "subagents" -> lsSubagents(segs, limit);
            default -> throw new NavNotFoundException(
                    "No such path: " + displayPath(segs) +
                    " (valid top-level paths: /sessions, /decisions, /events, /subagents)");
        };
    }

    private List<Entry> lsSessions(List<String> segs, int limit) throws Exception {
        SessionStore sessionStore = new SessionStore(db);

        if (segs.size() == 1) {
            List<SearchResult> sessions   = sessionStore.list(null, limit);
            AgentSessionStore  agentStore = new AgentSessionStore(db);
            List<Entry>        out        = new ArrayList<>();
            for (SearchResult r : sessions) {
                boolean hasChildren = agentStore.hasChildren(r.getSessionId());
                out.add(new Entry(r.getSessionId(), "/sessions/" + r.getSessionId(),
                        hasChildren ? "directory" : "file", oneLineSession(r)));
            }
            return out;
        }

        Session parent = sessionStore.getByIdOrPrefix(segs.get(1));
        if (parent == null) throw new NavNotFoundException("No such session: " + segs.get(1));
        String denied = McpServer.governanceDenialReason(sessionStore, parent.getSessionId());
        if (denied != null)
            throw new NavNotFoundException("Session " + parent.getSessionId() + " is not accessible: " + denied);

        if (segs.size() == 2) {
            // Refresh the agent index first — mirrors kcp_memory_session_tree's toolSessionTree.
            new AgentSessionScanner(db).scan(false);
            List<AgentSession> agents = new AgentSessionStore(db).listByParent(parent.getSessionId(), limit);
            List<Entry>        out    = new ArrayList<>();
            for (AgentSession a : agents) {
                out.add(new Entry(a.getAgentId(),
                        "/sessions/" + parent.getSessionId() + "/" + a.getAgentId(),
                        "file", oneLineAgent(a)));
            }
            return out;
        }

        // Leaf path (a specific subagent) — ls on a file lists just that file, unix-style.
        String       agentId = segs.get(2);
        AgentSession agent   = new AgentSessionStore(db).getById(agentId);
        if (agent == null || !parent.getSessionId().equals(agent.getParentSessionId()))
            throw new NavNotFoundException("No such subagent: " + agentId + " under session " + parent.getSessionId());
        return List.of(new Entry(agent.getAgentId(),
                "/sessions/" + parent.getSessionId() + "/" + agent.getAgentId(),
                "file", oneLineAgent(agent)));
    }

    private List<Entry> lsDecisions(List<String> segs, int limit) throws SQLException {
        DecisionStore store = new DecisionStore(db);
        if (segs.size() == 1) {
            List<Entry> out = new ArrayList<>();
            for (Decision d : store.filter(null, null, limit)) {
                out.add(new Entry(d.id(), "/decisions/" + d.id(), "file", oneLineDecision(d)));
            }
            return out;
        }
        Decision d = store.getById(segs.get(1));
        if (d == null) throw new NavNotFoundException("No such decision: " + segs.get(1));
        return List.of(new Entry(d.id(), "/decisions/" + d.id(), "file", oneLineDecision(d)));
    }

    private List<Entry> lsEvents(List<String> segs, int limit) throws SQLException {
        EventStore store = new EventStore(db);
        if (segs.size() == 1) {
            List<Entry> out = new ArrayList<>();
            for (ToolEvent e : store.list(null, limit)) {
                out.add(new Entry(String.valueOf(e.id()), "/events/" + e.id(), "file", oneLineEvent(e)));
            }
            return out;
        }
        long      id = parseEventId(segs.get(1));
        ToolEvent e  = store.getById(id);
        if (e == null) throw new NavNotFoundException("No such event: " + segs.get(1));
        return List.of(new Entry(String.valueOf(e.id()), "/events/" + e.id(), "file", oneLineEvent(e)));
    }

    private List<Entry> lsSubagents(List<String> segs, int limit) throws SQLException {
        AgentSessionStore store = new AgentSessionStore(db);
        if (segs.size() == 1) {
            new AgentSessionScanner(db).scan(false);
            List<Entry> out = new ArrayList<>();
            for (AgentSession a : store.list(null, limit)) {
                out.add(new Entry(a.getAgentId(), "/subagents/" + a.getAgentId(), "file", oneLineAgent(a)));
            }
            return out;
        }
        AgentSession a = store.getById(segs.get(1));
        if (a == null) throw new NavNotFoundException("No such subagent: " + segs.get(1));
        return List.of(new Entry(a.getAgentId(), "/subagents/" + a.getAgentId(), "file", oneLineAgent(a)));
    }

    private String renderLs(String path, List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append(path).append("  (").append(entries.size())
          .append(entries.size() == 1 ? " entry)" : " entries)").append("\n\n");
        if (entries.isEmpty()) {
            sb.append("(empty)\n");
            return sb.toString();
        }
        for (Entry e : entries) {
            sb.append(e.kind().equals("directory") ? "d " : "- ").append(e.path()).append("\n");
            sb.append("    ").append(e.summary()).append("\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Leaf rendering
    // ------------------------------------------------------------------

    /**
     * Mirrors {@code McpServer#toolSessionDetail}'s output. Not extracted into
     * a shared method because that method is a private instance method with
     * no natural extraction seam without a larger refactor of McpServer; the
     * part that actually mattered for avoiding duplication — the query and
     * the governance gate — is shared via {@link SessionStore#getByIdOrPrefix}
     * and {@link McpServer#governanceDenialReason}, both called by the caller
     * of this method before it is invoked.
     */
    private String renderSessionDetail(Session s) {
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

    /** No existing tool exposes a single subagent's full detail — this is genuinely new. */
    private String renderAgentDetail(AgentSession a) {
        StringBuilder sb = new StringBuilder();
        sb.append("Subagent: ").append(a.getAgentId()).append("\n");
        sb.append("Parent session: ").append(a.getParentSessionId()).append("\n");
        if (a.getAgentSlug() != null)  sb.append("Slug:    ").append(a.getAgentSlug()).append("\n");
        if (a.getProjectDir() != null) sb.append("Project: ").append(a.getProjectDir()).append("\n");
        if (a.getCwd() != null)        sb.append("Cwd:     ").append(a.getCwd()).append("\n");
        if (a.getModel() != null)      sb.append("Model:   ").append(a.getModel()).append("\n");
        sb.append("Turns:   ").append(a.getTurnCount())
          .append("  Tool calls: ").append(a.getToolCallCount()).append("\n");
        if (a.getFirstSeenAt() != null)    sb.append("First seen:   ").append(a.getFirstSeenAt()).append("\n");
        if (a.getLastUpdatedAt() != null)  sb.append("Last updated: ").append(a.getLastUpdatedAt()).append("\n");

        if (a.getToolNames() != null && !a.getToolNames().isEmpty()) {
            sb.append("\nTools used: ").append(String.join(", ", a.getToolNames())).append("\n");
        }
        if (a.getAllUserText() != null && !a.getAllUserText().isBlank()) {
            sb.append("\nUser/task text:\n");
            String text = a.getAllUserText();
            if (text.length() > 4000) {
                sb.append(text, 0, 4000).append("\n… [truncated — ").append(text.length() - 4000).append(" more chars]\n");
            } else {
                sb.append(text).append("\n");
            }
        }
        return sb.toString();
    }

    /** Mirrors {@code McpServer#toolDecisions}'s per-decision block exactly. */
    private String renderDecisionDetail(Decision d) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(d.id()).append("\n");
        sb.append("**Type**: ").append(d.type()).append("  |  ");
        sb.append("**Domain**: ").append(d.domain()).append("\n");
        sb.append("**What**: ").append(d.what()).append("\n");
        sb.append("**Why**: ").append(d.why()).append("\n");
        if (d.alternatives() != null && !d.alternatives().isEmpty()) {
            sb.append("**Alternatives**:\n");
            for (String alt : d.alternatives()) sb.append("  - ").append(alt).append("\n");
        }
        sb.append("**Learned**: ").append(d.learned()).append("\n");
        if (d.updated() != null && !d.updated().isBlank()) sb.append("**Updated**: ").append(d.updated()).append("\n");
        if (d.tags() != null && !d.tags().isEmpty()) sb.append("**Tags**: ").append(String.join(", ", d.tags())).append("\n");
        sb.append("**Project**: ").append(d.projectPath()).append("\n");
        return sb.toString();
    }

    /** No existing tool exposes a single event's full detail — this is genuinely new. */
    private String renderEventDetail(ToolEvent e) {
        StringBuilder sb = new StringBuilder();
        sb.append("Event #").append(e.id()).append("\n");
        sb.append("Timestamp: ").append(e.eventTs()).append("\n");
        sb.append("Session:   ").append(e.sessionId()).append("\n");
        sb.append("Project:   ").append(e.projectDir()).append("\n");
        sb.append("Tool:      ").append(e.tool()).append("\n");
        if (e.manifestKey() != null || e.manifestVersion() != null) {
            sb.append("Manifest:  ").append(e.manifestKey() != null ? e.manifestKey() : "?");
            if (e.manifestVersion() != null) sb.append("  (version ").append(e.manifestVersion()).append(")");
            sb.append("\n");
        }
        sb.append("Command:\n  ").append(e.command()).append("\n");
        if (e.outputPreview() != null) sb.append("\nOutput preview:\n  ").append(e.outputPreview()).append("\n");
        sb.append("\nIngested: ").append(e.ingestedAt()).append("\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // One-line summaries (directory entries)
    // ------------------------------------------------------------------

    private String oneLineSession(SearchResult r) {
        String date = r.getStartedAt() != null ? r.getStartedAt().substring(0, 10) : "?";
        String msg  = truncate(r.getFirstMessage(), 80);
        return date + "  " + r.getProjectDir()
                + "  turns=" + r.getTurnCount() + " tools=" + r.getToolCallCount()
                + (msg != null ? "  \"" + msg + "\"" : "");
    }

    private String oneLineAgent(AgentSession a) {
        String msg = truncate(a.getFirstMessage(), 80);
        return "[sub of " + shortId(a.getParentSessionId()) + "]"
                + "  turns=" + a.getTurnCount() + " tools=" + a.getToolCallCount()
                + (a.getCwd() != null ? "  " + a.getCwd() : "")
                + (msg != null ? "  \"" + msg + "\"" : "");
    }

    private String oneLineDecision(Decision d) {
        return "[" + d.type() + "/" + d.domain() + "]  " + truncate(d.what(), 100);
    }

    private String oneLineEvent(ToolEvent e) {
        String ts  = e.eventTs() != null && e.eventTs().length() >= 19
                ? e.eventTs().substring(0, 19).replace('T', ' ') : String.valueOf(e.eventTs());
        return ts + "  " + e.projectDir() + "  $ " + truncate(e.command(), 80);
    }

    // ------------------------------------------------------------------
    // Trajectory logging (fail-safe: never throws into the caller)
    // ------------------------------------------------------------------

    private void logTrajectory(String tool, String path, String sessionId, String summary) {
        try {
            new NavTrajectoryStore(db).record(tool, path, sessionId, summary);
        } catch (Exception e) {
            // Log-and-swallow, matching UsageLogger's established convention for
            // non-critical side effects elsewhere in this codebase. The
            // ls/tree/read response has already been computed by the time this
            // runs — a logging failure must never turn into a tool failure.
            System.err.println("[kcp-memory mcp] nav trajectory log failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private static List<String> segments(String path) {
        if (path == null) return List.of();
        String p = path.trim();
        while (p.startsWith("/")) p = p.substring(1);
        while (p.endsWith("/"))   p = p.substring(0, p.length() - 1);
        if (p.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : p.split("/+")) {
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private static String displayPath(List<String> segs) {
        return segs.isEmpty() ? "/" : "/" + String.join("/", segs);
    }

    private static String optionalText(JsonNode args, String field) {
        String v = args.path(field).asText("").strip();
        return v.isEmpty() ? null : v;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String shortId(String id) {
        return id == null ? "?" : id.substring(0, Math.min(8, id.length()));
    }

    private static long parseEventId(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new NavNotFoundException("Invalid event id: " + s);
        }
    }

    private static String humanSize(int chars) {
        return chars < 1024 ? chars + "B" : String.format("%.1fKB", chars / 1024.0);
    }

    /** A single virtual directory/file entry returned by {@code ls}/{@code tree}. */
    private record Entry(String name, String path, String kind, String summary) {}

    /** Internal signal for "no such virtual path" / "not accessible" — never escapes this class. */
    private static final class NavNotFoundException extends RuntimeException {
        NavNotFoundException(String message) { super(message); }
    }
}
