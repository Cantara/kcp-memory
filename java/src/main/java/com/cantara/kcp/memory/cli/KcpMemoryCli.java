package com.cantara.kcp.memory.cli;

import com.cantara.kcp.memory.KcpMemoryDaemon;
import com.cantara.kcp.memory.model.SearchResult;
import com.cantara.kcp.memory.model.ToolEvent;
import com.cantara.kcp.memory.scanner.EventLogScanner;
import com.cantara.kcp.memory.scanner.SessionScanner;
import com.cantara.kcp.memory.store.EventStore;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.SessionStore;
import com.cantara.kcp.memory.store.ToolUsageStore;
import picocli.CommandLine;
import picocli.CommandLine.*;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * kcp-memory CLI — subcommands: daemon, scan, search, list, stats
 */
@Command(
        name = "kcp-memory",
        mixinStandardHelpOptions = true,
        version = "0.2.0",
        description = "Episodic memory for Claude Code — index and query session history",
        subcommands = {
                KcpMemoryCli.DaemonCmd.class,
                KcpMemoryCli.ScanCmd.class,
                KcpMemoryCli.SearchCmd.class,
                KcpMemoryCli.ListCmd.class,
                KcpMemoryCli.StatsCmd.class,
                KcpMemoryCli.EventsCmd.class
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

        @Override
        public Integer call() throws Exception {
            try (MemoryDatabase db = new MemoryDatabase()) {
                SessionScanner scanner = new SessionScanner(db);
                System.out.println("[kcp-memory] scanning...");
                SessionScanner.ScanResult result = scanner.scan(force);
                System.out.printf("[kcp-memory] scan complete: %d indexed, %d skipped, %d errors%n",
                        result.indexed(), result.skipped(), result.errors());
                if (result.hasErrors()) {
                    result.errorMessages().forEach(e -> System.err.println("  ERROR: " + e));
                    return 1;
                }
                return 0;
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

                SessionStore.Stats stats = sessionStore.stats();
                var topTools = toolStore.topTools(10);

                System.out.println("[kcp-memory] statistics");
                System.out.println("─────────────────────────────────");
                System.out.printf("  Sessions:    %,d%n", stats.totalSessions());
                System.out.printf("  Turns:       %,d%n", stats.totalTurns());
                System.out.printf("  Tool calls:  %,d%n", stats.totalToolCalls());
                System.out.printf("  Oldest:      %s%n", nullSafe(stats.oldest()));
                System.out.printf("  Newest:      %s%n", nullSafe(stats.newest()));
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
