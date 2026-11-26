package com.cantara.kcp.memory.handler;

import com.cantara.kcp.memory.scanner.SessionScanner;
import com.cantara.kcp.memory.scanner.FollowUpScan;
import com.cantara.kcp.memory.scanner.SingleFlightScanTrigger;
import com.cantara.kcp.memory.server.TcpExchange;
import com.cantara.kcp.memory.store.MemoryDatabase;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
        this(triggerWithFollowUp(new SessionScanner(db)));
    }

    /**
     * #105: when a scan leaves a large, recently parsed transcript for later, request one
     * more scan when that file is due. Otherwise it would wait for the next request or
     * for the 30-minute background scan.
     */
    private static SingleFlightScanTrigger triggerWithFollowUp(SessionScanner scanner) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kcp-memory-scan-follow-up");
            t.setDaemon(true);
            return t;
        });
        SingleFlightScanTrigger[] trigger = new SingleFlightScanTrigger[1];
        FollowUpScan followUp = new FollowUpScan(scheduler, () -> trigger[0].request(false));
        trigger[0] = new SingleFlightScanTrigger(
                force -> {
                    SessionScanner.ScanResult result = scanner.scan(force);
                    if (result.deferred() > 0) followUp.after(result.retryAfterMs());
                },
                r -> Thread.ofVirtual().name("kcp-memory-scan").start(r));
        return trigger[0];
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
