package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.scanner.SessionScanner;
import com.cantara.kcp.memory.store.MemoryDatabase;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /scan[?force=true] — trigger an incremental (or full) scan.
 * Fire-and-forget: returns 202 immediately; scan runs in background.
 */
public class ScanHandler extends BaseHandler {

    private final SessionScanner scanner;

    public ScanHandler(MemoryDatabase db) {
        this.scanner = new SessionScanner(db);
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }
        Map<String, String> params = queryParams(ex);
        boolean force = "true".equalsIgnoreCase(params.get("force"));

        // Respond immediately, run scan in virtual thread
        sendJson(ex, 202, Map.of("status", "scanning", "force", force));

        Thread.ofVirtual().start(() -> {
            SessionScanner.ScanResult result = scanner.scan(force);
            // Result is visible via /stats endpoint; not pushed to caller
        });
    }
}
