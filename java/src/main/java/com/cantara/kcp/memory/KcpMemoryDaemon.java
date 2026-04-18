package com.cantara.kcp.memory;

import com.cantara.kcp.memory.handler.*;
import com.cantara.kcp.memory.mcp.McpServer;
import com.cantara.kcp.memory.peer.NodeRegistry;
import com.cantara.kcp.memory.peer.PeerSyncService;
import com.cantara.kcp.memory.peer.TaskExecutor;
import com.cantara.kcp.memory.scanner.AgentSessionScanner;
import com.cantara.kcp.memory.scanner.EventLogScanner;
import com.cantara.kcp.memory.scanner.SessionFileWatcher;
import com.cantara.kcp.memory.scanner.SessionScanner;
import com.cantara.kcp.memory.server.EventBroadcaster;
import com.cantara.kcp.memory.server.ExternalHttpServer;
import com.cantara.kcp.memory.server.TcpHttpServer;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.cantara.kcp.memory.store.PendingTagsStore;
import com.cantara.kcp.memory.store.PendingTaskStore;
import com.cantara.kcp.memory.store.SessionStore;
import com.cantara.kcp.memory.update.UpdateChecker;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * HTTP daemon — listens on localhost:7735.
 *
 * <p>Uses a plain {@link TcpHttpServer} (ServerSocket + virtual threads) instead of
 * {@code com.sun.net.httpserver.HttpServer}, avoiding the NIO selector dependency
 * ({@code WEPollSelectorImpl} / AF_UNIX pipes) that fails inside Windows MSIX sandboxes.
 *
 * <p>Endpoints:
 * <pre>
 *   GET  /health              — liveness + session count
 *   GET  /search?q=...        — FTS5 full-text search
 *   GET  /sessions            — list recent sessions
 *   GET  /stats               — aggregate statistics
 *   POST /scan                — trigger incremental scan (fire-and-forget)
 *   GET  /events/search       — tool-call event search
 *   GET  /files?path=...      — directory listing (dirs-first, sorted)
 *   GET  /files/content?path= — read file content (max 1MB, UTF-8)
 *   POST /process             — systemctl action on named service
 * </pre>
 */
public class KcpMemoryDaemon {

    private static final Logger LOG = Logger.getLogger(KcpMemoryDaemon.class.getName());
    public  static final int    PORT = 7735;

    private final MemoryDatabase db;
    private final NodeRegistry nodeRegistry = new NodeRegistry();
    private final EventBroadcaster broadcaster = new EventBroadcaster();
    private TcpHttpServer server;
    private ScheduledExecutorService scheduler;
    private final List<PeerSyncService> peerSyncServices = new ArrayList<>();
    private ExternalHttpServer externalServer;
    private SessionFileWatcher fileWatcher;
    private String nodeName;
    private TaskExecutor taskExecutor;
    private List<String> nodeCapabilities = List.of();

    public KcpMemoryDaemon(MemoryDatabase db) {
        this.db = db;
    }

    /**
     * Set a friendly display name for this node (shown in /nodes listings).
     * Call before start() / startPeerSync() / startExternalServer().
     */
    public void setNodeName(String name) {
        this.nodeName = name;
    }

    /** Override the task executor used by peer sync (default: ClaudeTaskExecutor). */
    public void setTaskExecutor(TaskExecutor executor) {
        this.taskExecutor = executor;
    }

    /** Set capabilities advertised in node self-registration (e.g. ["claude"] or ["ironclaw", "deepseek/deepseek-v3.2"]). */
    public void setCapabilities(List<String> capabilities) {
        this.nodeCapabilities = capabilities != null ? capabilities : List.of();
    }

    public void start() throws Exception {
        server = new TcpHttpServer(PORT);

        IngestHandler ingestHandler = new IngestHandler(db);
        ingestHandler.setBroadcaster(broadcaster);

        server.createContext("/health",        new HealthHandler(db));
        server.createContext("/search",        new SearchHandler(db));
        server.createContext("/sessions",      new ListHandler(db));
        server.createContext("/stats",         new StatsHandler(db));
        server.createContext("/scan",          new ScanHandler(db));
        server.createContext("/events/search", new EventsHandler(db));
        server.createContext("/ingest",        ingestHandler);
        server.createContext("/nodes",         new NodesHandler(nodeRegistry));
        server.createContext("/nodes/register", new NodeRegisterHandler(nodeRegistry));

        // Session content (JSONL transcript reader)
        server.createContext("/sessions/", new SessionContentHandler(db));

        // File browser (hub-local)
        FileHandler fileHandler = new FileHandler();
        server.createContext("/files", fileHandler);

        // Process control (hub-local)
        ProcessHandler processHandler = new ProcessHandler();
        server.createContext("/process", processHandler);

        // Pending task queue (peer dispatch)
        PendingTaskStore pendingStore = new PendingTaskStore(db);
        PendingDispatchHandler pendingHandler = new PendingDispatchHandler(pendingStore);
        server.createContext("/dispatch/queue", pendingHandler);
        server.createContext("/pending", pendingHandler);

        // Auto-tagging: pending tags from UserPromptSubmit hook
        server.createContext("/tags/pending", new TagsPendingHandler(db));

        server.start();
        LOG.info("kcp-memory daemon started on port " + PORT);

        // Startup update check — non-blocking, once per 24h
        Thread.ofVirtual().start(() -> {
            try {
                String currentMem = McpServer.SERVER_VERSION;
                UpdateChecker.Versions v = new UpdateChecker().checkIfDue(currentMem, currentMem);
                if (v.memoryOutdated())
                    LOG.warning("[kcp-memory] Update available: " + currentMem + " → " + v.latestMemory()
                            + "  (run: java -jar ~/.kcp/kcp-memory-daemon.jar update)");
                if (v.commandsOutdated())
                    LOG.warning("[kcp-memory] kcp-commands update available: " + v.currentCommands()
                            + " → " + v.latestCommands());
            } catch (Exception e) {
                LOG.fine("Update check failed: " + e.getMessage());
            }
        });

        // Initial scans on startup (includes agent sessions + pending tag flush)
        Thread.ofVirtual().start(() -> {
            LOG.info("Running initial session scan on startup...");
            new SessionScanner(db).scan(false);
            LOG.info("Running initial agent session scan on startup...");
            new AgentSessionScanner(db).scan(false);
            LOG.info("Running initial event log scan on startup...");
            new EventLogScanner(db).scan();
            try {
                new PendingTagsStore(db).flush(new SessionStore(db));
            } catch (Exception e) {
                LOG.fine("Pending tag flush failed: " + e.getMessage());
            }
        });

        // Live JSONL file watcher — broadcasts new messages to WebSocket clients
        fileWatcher = new SessionFileWatcher(broadcaster);
        fileWatcher.start();

        // Background scan every 30 minutes
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kcp-memory-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            new SessionScanner(db).scan(false);
            new AgentSessionScanner(db).scan(false);
            new EventLogScanner(db).scan();
            try {
                new PendingTagsStore(db).flush(new SessionStore(db));
            } catch (Exception e) {
                LOG.fine("Pending tag flush failed: " + e.getMessage());
            }
        }, 30, 30, TimeUnit.MINUTES);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down kcp-memory daemon...");
            scheduler.shutdownNow();
            if (fileWatcher != null) fileWatcher.stop();
            peerSyncServices.forEach(PeerSyncService::stop);
            if (externalServer != null) externalServer.stop();
            server.stop();
            try { db.close(); } catch (SQLException ignored) {}
        }));
    }

    /**
     * Start bidirectional peer sync. Call after start(). Repeatable.
     *
     * @param peerUri         ssh://user@host or tcp://host:port
     * @param localInstanceId this instance's identifier (hostname)
     */
    public void startPeerSync(String peerUri, String localInstanceId) {
        PeerSyncService sync = new PeerSyncService(db, peerUri, localInstanceId);
        sync.setNodeRegistry(nodeRegistry);
        if (nodeName != null && !nodeName.isBlank()) {
            sync.setDisplayName(nodeName);
        }
        if (taskExecutor != null) {
            sync.setTaskExecutor(taskExecutor);
        }
        sync.setCapabilities(nodeCapabilities);
        sync.start();
        peerSyncServices.add(sync);
    }

    /**
     * Start the external API server for mobile access. Call after start().
     */
    public void startExternalServer(String bindAddress, int port,
                                     String tlsCert, String tlsKey,
                                     String apiKey, String captureDir,
                                     String synthesisCmd) throws Exception {
        externalServer = new ExternalHttpServer(bindAddress, port, tlsCert, tlsKey, apiKey);

        // Register all internal endpoints (same data, external auth)
        externalServer.createContext("/health", new HealthHandler(db));
        externalServer.createContext("/search", new SearchHandler(db));
        externalServer.createContext("/sessions", new ListHandler(db));
        externalServer.createContext("/sessions/", new SessionContentHandler(db));
        externalServer.createContext("/stats", new StatsHandler(db));
        externalServer.createContext("/events/search", new EventsHandler(db));

        // Control plane endpoints
        externalServer.createContext("/nodes", new NodesHandler(nodeRegistry));
        externalServer.createContext("/nodes/register", new NodeRegisterHandler(nodeRegistry));
        externalServer.createContext("/ws", new WsHandler(broadcaster));

        // File browser + process control (hub-local, available externally)
        externalServer.createContext("/files", new FileHandler());
        externalServer.createContext("/process", new ProcessHandler());

        // Pending task queue (peer dispatch — available externally for mobile)
        PendingTaskStore pendingStore = new PendingTaskStore(db);
        PendingDispatchHandler pendingHandler = new PendingDispatchHandler(pendingStore);
        externalServer.createContext("/dispatch/queue", pendingHandler);
        externalServer.createContext("/pending", pendingHandler);

        // Mobile-specific endpoints
        externalServer.createContext("/dispatch", new DispatchHandler());
        externalServer.createContext("/capture",
                new CaptureHandler(Path.of(captureDir != null ? captureDir : System.getProperty("user.home") + "/.kcp/captures")));
        externalServer.createContext("/synthesis/search",
                new SynthesisProxyHandler(synthesisCmd != null ? synthesisCmd : "synthesis search"));

        externalServer.start();

        // Self-register hub in its own NodeRegistry so it appears in /nodes
        try {
            String hubId = java.net.InetAddress.getLocalHost().getHostName();
            String hubDisplayName = (nodeName != null && !nodeName.isBlank()) ? nodeName : hubId;
            long hubSessions = 0;
            try { hubSessions = new SessionStore(db).stats().totalSessions(); } catch (Exception ignored) {}
            nodeRegistry.register(hubId, "local", hubDisplayName);
            nodeRegistry.markSeen(hubId);
            nodeRegistry.updateHealth(hubId, hubSessions, 0);
        } catch (Exception e) {
            // non-fatal
        }
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        peerSyncServices.forEach(PeerSyncService::stop);
        if (externalServer != null) externalServer.stop();
        if (server    != null) server.stop();
    }
}
