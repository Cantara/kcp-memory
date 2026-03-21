package com.cantara.kcp.memory.cli;

import com.cantara.kcp.memory.KcpMemoryDaemon;
import com.cantara.kcp.memory.mcp.McpServer;
import com.cantara.kcp.memory.model.AgentSession;
import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.model.ToolEvent;
import com.cantara.kcp.memory.scanner.AgentSessionScanner;
import com.cantara.kcp.memory.scanner.EventLogScanner;
import com.cantara.kcp.memory.scanner.SessionScanner;
import com.cantara.kcp.memory.store.AgentSessionStore;
import com.cantara.kcp.memory.store.EventStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.cantara.kcp.memory.store.ToolUsageStore;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * kcp-memory CLI — subcommands: daemon, scan, search, list, stats, events, agents, mcp
 */
@Command(
        name = "kcp-memory",
        mixinStandardHelpOptions = true,
        version = "0.5.0",
        description = "Episodic memory for Claude Code — index and query session history",
        subcommands = {
                KcpMemoryCli.DaemonCmd.class,
                KcpMemoryCli.ScanCmd.class,
                KcpMemoryCli.SearchCmd.class,
                KcpMemoryCli.ListCmd.class,
                KcpMemoryCli.StatsCmd.class,
                KcpMemoryCli.EventsCmd.class,
                KcpMemoryCli.AgentsCmd.class,
                KcpMemoryCli.McpCmd.class
        }
)
public class KcpMemoryCli implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    // ------------------------------------------------------------------
    // daemon — start the HTTP daemon
    // ------------------------------------------------------------------
    @Command(name = "daemon", description = "Start the kcp-memory HTTP daemon on port 7735")
    static class DaemonCmd implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            MemoryDatabase db = new MemoryDatabase();
            KcpMemoryDaemon daemon = new KcpMemoryDaemon(db);
            daemon.start();
            System.out.printf("[kcp-memory] daemon running on port %d — press Ctrl+C to stop%n",
                    KcpMemoryDaemon.PORT);
            Thread.currentThread().join(); // block until killed
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // scan — index session transcripts
    // ------------------------------------------------------------------
    @Command(name = "scan", description = "Scan ~/.claude/projects/ and index new/changed sessions")
    static class ScanCmd implements Callable<Integer> {

        @Option(names = {"--force", "-f"}, description = "Re-index all sessions, not just new ones")
        boolean force;

        @Option(names = {"--include-agents"}, description = "Also scan subagent transcripts (default: true)",
                defaultValue = "true", negatable = true)
        boolean includeAgents;

        @Override
        public Integer call() throws Exception {
            try (MemoryDatabase db = new MemoryDatabase()) {
                // Main session scan
                SessionScanner scanner = new SessionScanner(db);
                System.out.println("[kcp-memory] scanning sessions...");
                SessionScanner.ScanResult result = scanner.scan(force);
                System.out.printf("[kcp-memory] sessions: %d indexed, %d skipped, %d errors%n",
                        result.indexed(), result.skipped(), result.errors());
                if (result.hasErrors()) {
                    result.errorMessages().forEach(e -> System.err.println("  ERROR: " + e));
                }

                // Agent session scan
                if (includeAgents) {
                    AgentSessionScanner agentScanner = new AgentSessionScanner(db);
                    System.out.println("[kcp-memory] scanning agent sessions...");
                    AgentSessionScanner.ScanResult agentResult = agentScanner.scan(force);
                    System.out.printf("[kcp-memory] agents:   %d indexed, %d skipped, %d errors%n",
                            agentResult.indexed(), agentResult.skipped(), agentResult.errors());
                    if (agentResult.hasErrors()) {
                        agentResult.errorMessages().forEach(e -> System.err.println("  ERROR: " + e));
                    }
                }

                return result.hasErrors() ? 1 : 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // search — full-text search
    // ------------------------------------------------------------------
    @Command(name = "search", description = "Search session transcripts using full-text search")
    static class SearchCmd implements Callable<Integer> {

        @Parameters(arity = "1..*", description = "Search query")
        List<String> queryWords;

        @Option(names = {"--limit", "-n"}, description = "Max results (default: 10)", defaultValue = "10")
        int limit;

        @Override
        public Integer call() throws Exception {
            String query = String.join(" ", queryWords);
            try (MemoryDatabase db = new MemoryDatabase()) {
                SessionStore store = new SessionStore(db);
                List<SearchResult> results = store.search(query, limit);
                if (results.isEmpty()) {
                    System.out.println("[kcp-memory] no results for: " + query);
                    return 0;
                }
                System.out.printf("[kcp-memory] %d result(s) for \"%s\"%n%n", results.size(), query);
                for (SearchResult r : results) {
                    printSession(r);
                }
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // list — list recent sessions
    // ------------------------------------------------------------------
    @Command(name = "list", description = "List recent Claude Code sessions")
    static class ListCmd implements Callable<Integer> {

        @Option(names = {"--project", "-p"}, description = "Filter by project directory")
        String project;

        @Option(names = {"--limit", "-n"}, description = "Max results (default: 20)", defaultValue = "20")
        int limit;

        @Override
        public Integer call() throws Exception {
            try (MemoryDatabase db = new MemoryDatabase()) {
                SessionStore store = new SessionStore(db);
                List<SearchResult> sessions = store.list(project, limit);
                if (sessions.isEmpty()) {
                    System.out.println("[kcp-memory] no sessions indexed yet. Run: kcp-memory scan");
                    return 0;
                }
                System.out.printf("[kcp-memory] %d session(s)%s%n%n",
                        sessions.size(),
                        project != null ? " in " + project : "");
                for (SearchResult r : sessions) {
                    printSession(r);
                }
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // stats — aggregate statistics
    // ------------------------------------------------------------------
    @Command(name = "stats", description = "Show aggregate statistics across all indexed sessions")
    static class StatsCmd implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            try (MemoryDatabase db = new MemoryDatabase()) {
                SessionStore sessionStore = new SessionStore(db);
                ToolUsageStore toolStore  = new ToolUsageStore(db);
                AgentSessionStore agentStore = new AgentSessionStore(db);

                SessionStore.Stats stats = sessionStore.stats();
                AgentSessionStore.Stats agentStats = agentStore.stats();
                var topTools = toolStore.topTools(10);

                System.out.println("[kcp-memory] statistics");
                System.out.println("─────────────────────────────────");
                System.out.printf("  Sessions:    %,d%n", stats.totalSessions());
                System.out.printf("  Turns:       %,d%n", stats.totalTurns());
                System.out.printf("  Tool calls:  %,d%n", stats.totalToolCalls());
                System.out.printf("  Oldest:      %s%n", nullSafe(stats.oldest()));
                System.out.printf("  Newest:      %s%n", nullSafe(stats.newest()));
                System.out.println();
                System.out.println("  Subagents:");
                System.out.printf("    Agents:          %,d%n", agentStats.totalAgents());
                System.out.printf("    Parent sessions: %,d%n", agentStats.parentSessions());
                System.out.printf("    Agent turns:     %,d%n", agentStats.totalTurns());
                System.out.printf("    Agent tool calls:%,d%n", agentStats.totalToolCalls());
                System.out.printf("    Agent messages:  %,d%n", agentStats.totalMessages());
                System.out.println();
                System.out.println("  Top tools:");
                topTools.forEach(t ->
                        System.out.printf("    %-25s %,d%n", t.toolName(), t.count()));
                return 0;
            }
        }

        private String nullSafe(String s) { return s != null ? s : "—"; }
    }

    // ------------------------------------------------------------------
    // events — search tool-call events from ~/.kcp/events.jsonl
    // ------------------------------------------------------------------
    @Command(name = "events", description = "Search tool-call events indexed from ~/.kcp/events.jsonl",
             subcommands = { KcpMemoryCli.EventsCmd.EventsSearchCmd.class })
    static class EventsCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }

        @Command(name = "search", description = "Full-text search over indexed tool-call events")
        static class EventsSearchCmd implements Callable<Integer> {

            @Parameters(arity = "1..*", description = "Search query")
            List<String> queryWords;

            @Option(names = {"--limit", "-n"}, description = "Max results (default: 20)", defaultValue = "20")
            int limit;

            @Override
            public Integer call() throws Exception {
                String query = String.join(" ", queryWords);
                try (MemoryDatabase db = new MemoryDatabase()) {
                    // Ingest any new events before searching
                    new EventLogScanner(db).scan();

                    EventStore store = new EventStore(db);
                    List<ToolEvent> results = store.search(query, limit);
                    if (results.isEmpty()) {
                        System.out.println("[kcp-memory] no events for: " + query);
                        return 0;
                    }
                    System.out.printf("[kcp-memory] %d event(s) for \"%s\"%n%n", results.size(), query);
                    for (ToolEvent e : results) {
                        printEvent(e);
                    }
                    return 0;
                }
            }
        }
    }

    private static void printEvent(ToolEvent e) {
        System.out.printf("  %s  %s%n",
                e.eventTs() != null ? e.eventTs().substring(0, 19).replace('T', ' ') : "?",
                e.projectDir());
        System.out.printf("  %s  [%s]%n",
                e.sessionId().length() > 8 ? e.sessionId().substring(0, 8) : e.sessionId(),
                e.manifestKey() != null ? e.manifestKey() : "no-manifest");
        String cmd = e.command();
        if (cmd.length() > 120) cmd = cmd.substring(0, 120) + "…";
        System.out.printf("  $ %s%n%n", cmd);
    }

    // ------------------------------------------------------------------
    // agents — list and search subagent sessions
    // ------------------------------------------------------------------
    @Command(name = "agents", description = "List and search subagent sessions",
             subcommands = {
                     KcpMemoryCli.AgentsCmd.AgentsListCmd.class,
                     KcpMemoryCli.AgentsCmd.AgentsSearchCmd.class
             })
    static class AgentsCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }

        @Command(name = "list", description = "List subagent sessions, optionally filtered by parent session")
        static class AgentsListCmd implements Callable<Integer> {

            @Option(names = {"--session", "-s"}, description = "Filter by parent session ID")
            String sessionId;

            @Option(names = {"--limit", "-n"}, description = "Max results (default: 20)", defaultValue = "20")
            int limit;

            @Override
            public Integer call() throws Exception {
                try (MemoryDatabase db = new MemoryDatabase()) {
                    AgentSessionStore store = new AgentSessionStore(db);
                    List<AgentSession> agents;
                    if (sessionId != null && !sessionId.isBlank()) {
                        agents = store.listByParent(sessionId, limit);
                    } else {
                        agents = store.list(null, limit);
                    }
                    if (agents.isEmpty()) {
                        System.out.println("[kcp-memory] no agent sessions found. Run: kcp-memory scan");
                        return 0;
                    }
                    System.out.printf("[kcp-memory] %d agent session(s)%s%n%n",
                            agents.size(),
                            sessionId != null ? " for session " + sessionId.substring(0, Math.min(8, sessionId.length())) : "");
                    for (AgentSession a : agents) {
                        printAgent(a);
                    }
                    return 0;
                }
            }
        }

        @Command(name = "search", description = "Search subagent transcripts using full-text search")
        static class AgentsSearchCmd implements Callable<Integer> {

            @Parameters(arity = "1..*", description = "Search query")
            List<String> queryWords;

            @Option(names = {"--limit", "-n"}, description = "Max results (default: 10)", defaultValue = "10")
            int limit;

            @Override
            public Integer call() throws Exception {
                String query = String.join(" ", queryWords);
                try (MemoryDatabase db = new MemoryDatabase()) {
                    AgentSessionStore store = new AgentSessionStore(db);
                    List<AgentSession> results = store.search(query, limit);
                    if (results.isEmpty()) {
                        System.out.println("[kcp-memory] no agent sessions for: " + query);
                        return 0;
                    }
                    System.out.printf("[kcp-memory] %d agent session(s) for \"%s\"%n%n",
                            results.size(), query);
                    for (AgentSession a : results) {
                        printAgent(a);
                    }
                    return 0;
                }
            }
        }
    }

    private static void printAgent(AgentSession a) {
        String date = a.getFirstSeenAt() != null ? a.getFirstSeenAt().substring(0, 10) : "?";
        System.out.printf("  %s  %s%n", date,
                a.getAgentSlug() != null ? a.getAgentSlug() : a.getAgentId());
        System.out.printf("  agent=%s  parent=%s  turns=%d  tools=%d  msgs=%d%n",
                a.getAgentId().substring(0, Math.min(8, a.getAgentId().length())),
                a.getParentSessionId().substring(0, Math.min(8, a.getParentSessionId().length())),
                a.getTurnCount(), a.getToolCallCount(), a.getMessageCount());
        if (a.getCwd() != null) {
            System.out.printf("  cwd=%s%n", a.getCwd());
        }
        if (a.getFirstMessage() != null) {
            String msg = a.getFirstMessage();
            if (msg.length() > 120) msg = msg.substring(0, 120) + "…";
            System.out.printf("  \"%s\"%n", msg);
        }
        System.out.println();
    }

    // ------------------------------------------------------------------
    // mcp — stdio MCP server for Claude Code integration
    // ------------------------------------------------------------------
    @Command(name = "mcp", description = "Start the kcp-memory MCP stdio server (for ~/.claude/settings.json mcpServers)")
    static class McpCmd implements Callable<Integer> {

        @Override
        public Integer call() throws Exception {
            // JUL ConsoleHandler already writes to System.err by default —
            // stdout is the MCP protocol channel and must not be polluted.
            MemoryDatabase db = new MemoryDatabase();
            new McpServer(db).run();
            db.close();
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Shared formatting
    // ------------------------------------------------------------------
    private static void printSession(SearchResult r) {
        System.out.printf("  %s  %s%n", r.getStartedAt() != null ? r.getStartedAt().substring(0, 10) : "?",
                r.getProjectDir());
        System.out.printf("  %s  turns=%d  tools=%d%n",
                r.getSessionId().substring(0, Math.min(8, r.getSessionId().length())),
                r.getTurnCount(), r.getToolCallCount());
        if (r.getFirstMessage() != null) {
            String msg = r.getFirstMessage();
            if (msg.length() > 120) msg = msg.substring(0, 120) + "…";
            System.out.printf("  \"%s\"%n", msg);
        }
        System.out.println();
    }
}
