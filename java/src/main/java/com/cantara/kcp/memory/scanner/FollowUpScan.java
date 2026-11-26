package com.cantara.kcp.memory.scanner;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Requests one more scan after a delay (#105), so a transcript whose re-parse was
 * delayed is still indexed when no further scan request arrives. At most one follow-up
 * is pending at a time.
 */
public final class FollowUpScan {

    private final ScheduledExecutorService scheduler;
    private final Runnable request;
    private final AtomicBoolean pending = new AtomicBoolean();

    public FollowUpScan(ScheduledExecutorService scheduler, Runnable request) {
        this.scheduler = scheduler;
        this.request = request;
    }

    /** Request a scan after {@code delayMs}, unless a follow-up is already pending. */
    public void after(long delayMs) {
        if (!pending.compareAndSet(false, true)) return;
        try {
            scheduler.schedule(() -> {
                pending.set(false);
                request.run();
            }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            pending.set(false); // scheduler shut down: the periodic scan picks the file up
        }
    }
}
