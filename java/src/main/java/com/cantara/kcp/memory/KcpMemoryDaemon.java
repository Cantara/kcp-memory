package com.cantara.kcp.memory;

import com.cantara.kcp.memory.handler.*;
import com.cantara.kcp.memory.scanner.AgentSessionScanner;
import com.cantara.kcp.memory.scanner.EventLogScanner;
import com.cantara.kcp.memory.scanner.SessionScanner;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * HTTP daemon — listens on localhost:7735.
 * <p>
 * Endpoints:
 * <pre>
 *   GET  /health          — liveness + session count
 *   GET  /search?q=...    — FTS5 full-text search
 *   GET  /sessions        — list recent sessions
 *   GET  /stats           — aggregate statistics
 *   POST /scan            — trigger incremental scan (fire-and-forget)
 * </pre>
 */
public class KcpMemoryDaemon {

    private static final Logger LOG = Logger.getLogger(KcpMemoryDaemon.class.getName());
    public  static final int    PORT = 7735;

    private final MemoryDatabase db;
    private HttpServer server;
    private ScheduledExecutorService scheduler;

    public KcpMemoryDaemon(MemoryDatabase db) {
        this.db = db;
    }

    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/health",        new HealthHandler(db));
        server.createContext("/search",        new SearchHandler(db));
        server.createContext("/sessions",      new ListHandler(db));
        server.createContext("/stats",         new StatsHandler(db));
        server.createContext("/scan",          new ScanHandler(db));
        server.createContext("/events/search", new EventsHandler(db));

        server.start();
        LOG.info("kcp-memory daemon started on port " + PORT);

        // Initial scans on startup (includes agent sessions)
        Thread.ofVirtual().start(() -> {
            LOG.info("Running initial session scan on startup...");
            new SessionScanner(db).scan(false);
            LOG.info("Running initial agent session scan on startup...");
            new AgentSessionScanner(db).scan(false);
            LOG.info("Running initial event log scan on startup...");
            new EventLogScanner(db).scan();
        });

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
        }, 30, 30, TimeUnit.MINUTES);

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutting down kcp-memory daemon...");
            scheduler.shutdownNow();
            server.stop(2);
            try { db.close(); } catch (SQLException ignored) {}
        }));
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdownNow();
        if (server    != null) server.stop(2);
    }
}
