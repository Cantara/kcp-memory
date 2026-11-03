package com.cantara.kcp.memory.scanner;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Coalesces scan requests (#88): at most one scan runs at a time, and requests that
 * arrive while it runs are folded into a single follow-up scan instead of each
 * starting its own.
 * <p>
 * The follow-up run is needed because a request that arrives mid-scan may be about a
 * file the running scan has already passed. A burst of N requests during one scan
 * therefore costs at most two scans, not N. A {@code force} request is never lost: if
 * any coalesced request asked for force, the follow-up run is forced.
 */
public final class SingleFlightScanTrigger {

    private static final Logger LOG = Logger.getLogger(SingleFlightScanTrigger.class.getName());

    private final Consumer<Boolean> scan;
    private final Executor executor;

    private final Object lock = new Object();
    private boolean running;
    private boolean rerunRequested;
    private boolean rerunForce;

    /**
     * @param scan     the scan to run; receives the {@code force} flag
     * @param executor where the scan loop runs (the daemon uses a virtual thread per run)
     */
    public SingleFlightScanTrigger(Consumer<Boolean> scan, Executor executor) {
        this.scan = scan;
        this.executor = executor;
    }

    /**
     * Request a scan.
     *
     * @return true if this call started a scan, false if it was coalesced into the
     *         one already running (which will then run once more when it finishes)
     */
    public boolean request(boolean force) {
        synchronized (lock) {
            if (running) {
                rerunRequested = true;
                rerunForce |= force;
                return false;
            }
            running = true;
        }
        try {
            executor.execute(() -> runLoop(force));
        } catch (RuntimeException e) {
            synchronized (lock) {
                running = false;
                rerunRequested = false;
                rerunForce = false;
            }
            throw e;
        }
        return true;
    }

    /** True while a scan (or its coalesced follow-up) is running. */
    public boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    private void runLoop(boolean force) {
        boolean nextForce = force;
        while (true) {
            try {
                scan.accept(nextForce);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Scan failed", e);
            }
            synchronized (lock) {
                if (!rerunRequested) {
                    running = false;
                    return;
                }
                rerunRequested = false;
                nextForce = rerunForce;
                rerunForce = false;
            }
        }
    }
}
