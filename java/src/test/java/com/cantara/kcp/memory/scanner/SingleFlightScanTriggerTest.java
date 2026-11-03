package com.cantara.kcp.memory.scanner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** #88: a burst of scan requests during a scan must run one scan, plus at most one follow-up. */
class SingleFlightScanTriggerTest {

    /** A scan that blocks on a latch, recording each run's force flag and the max concurrency. */
    static class BlockingScan {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<Boolean> runs = new CopyOnWriteArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();

        void run(boolean force) {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            runs.add(force);
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        }
    }

    private static void awaitIdle(SingleFlightScanTrigger trigger) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (trigger.isRunning() && System.currentTimeMillis() < deadline) Thread.onSpinWait();
        assertFalse(trigger.isRunning(), "trigger did not go idle");
    }

    @Test
    void concurrentRequestsDuringAScanAreCoalescedIntoOneFollowUp() throws Exception {
        BlockingScan scan = new BlockingScan();
        SingleFlightScanTrigger trigger =
                new SingleFlightScanTrigger(scan::run, r -> Thread.ofVirtual().start(r));

        assertTrue(trigger.request(false), "the first request starts a scan");
        assertTrue(scan.started.await(5, TimeUnit.SECONDS));

        // 20 concurrent requests while the first scan is running.
        AtomicInteger startedNew = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> callers = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            callers.add(Thread.ofVirtual().start(() -> {
                try { go.await(); } catch (InterruptedException ignored) {}
                if (trigger.request(false)) startedNew.incrementAndGet();
            }));
        }
        go.countDown();
        for (Thread t : callers) t.join(5_000);

        assertEquals(0, startedNew.get(), "no request may start a second scan while one runs");
        assertEquals(1, scan.runs.size(), "still only the first scan has run");

        scan.release.countDown();
        awaitIdle(trigger);

        assertEquals(2, scan.runs.size(), "20 coalesced requests = exactly one follow-up scan");
        assertEquals(1, scan.maxInFlight.get(), "scans never overlap");
    }

    @Test
    void requestAfterTheScanFinishedStartsANewOne() throws Exception {
        BlockingScan scan = new BlockingScan();
        scan.release.countDown(); // scans return immediately
        SingleFlightScanTrigger trigger =
                new SingleFlightScanTrigger(scan::run, r -> Thread.ofVirtual().start(r));

        assertTrue(trigger.request(false));
        awaitIdle(trigger);
        assertTrue(trigger.request(false));
        awaitIdle(trigger);
        assertEquals(2, scan.runs.size());
    }

    @Test
    void aCoalescedForceRequestMakesTheFollowUpForced() throws Exception {
        BlockingScan scan = new BlockingScan();
        SingleFlightScanTrigger trigger =
                new SingleFlightScanTrigger(scan::run, r -> Thread.ofVirtual().start(r));

        trigger.request(false);
        assertTrue(scan.started.await(5, TimeUnit.SECONDS));
        assertFalse(trigger.request(false));
        assertFalse(trigger.request(true));
        assertFalse(trigger.request(false));
        scan.release.countDown();
        awaitIdle(trigger);

        assertEquals(List.of(false, true), scan.runs);
    }

    @Test
    void aFailingScanDoesNotWedgeTheTrigger() {
        AtomicInteger runs = new AtomicInteger();
        SingleFlightScanTrigger trigger = new SingleFlightScanTrigger(force -> {
            runs.incrementAndGet();
            throw new IllegalStateException("boom");
        }, Runnable::run);

        assertTrue(trigger.request(false));
        assertFalse(trigger.isRunning());
        assertTrue(trigger.request(false));
        assertEquals(2, runs.get());
    }
}
