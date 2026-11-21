package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.scanner.SessionScanner;
import com.cantara.kcp.memory.scanner.SingleFlightScanTrigger;
import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.MemoryDatabase;

import java.io.IOException;
import java.util.Map;

/**
 * POST /scan[?force=true] — trigger an incremental (or full) scan.
 * Fire-and-forget: returns 202 immediately; scan runs in background.
 * <p>
 * Single-flight (#88): a request that arrives while a scan runs does not start a
 * second, parallel scan. It is coalesced: the running scan runs once more when it
 * finishes, however many requests arrived meanwhile. The response then carries
 * {@code "coalesced": true}.
 */
public class ScanHandler extends BaseHandler {

    private final SingleFlightScanTrigger trigger;

    public ScanHandler(MemoryDatabase db) {
        this(new SingleFlightScanTrigger(
                new SessionScanner(db)::scan,
                r -> Thread.ofVirtual().name("kcp-memory-scan").start(r)));
    }

    ScanHandler(SingleFlightScanTrigger trigger) {
        this.trigger = trigger;
    }

    @Override
    public void handle(TcpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "Method not allowed");
            return;
        }
        Map<String, String> params = queryParams(ex);
        boolean force = "true".equalsIgnoreCase(params.get("force"));

        // Result is visible via /stats endpoint; not pushed to caller
        boolean started = trigger.request(force);
        sendJson(ex, 202, Map.of("status", "scanning", "force", force, "coalesced", !started));
    }
}
