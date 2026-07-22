package com.cantara.kcp.memory.store;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class GovernanceGateTest {

    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

    @Test
    void allowsWhenNoConstraints() {
        GovernanceGate.Decision d = GovernanceGate.evaluate(null, null, null, NOW);
        assertTrue(d.allowed());
        assertNull(d.reason());
    }

    @Test
    void skipsForgottenMemoryWithReason() {
        GovernanceGate.Decision d =
                GovernanceGate.evaluate(null, "2026-07-20T00:00:00Z", "contained a secret", NOW);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("forgotten"));
        assertTrue(d.reason().contains("contained a secret"));
    }

    @Test
    void forgottenWithoutReasonStillHasAuditText() {
        GovernanceGate.Decision d =
                GovernanceGate.evaluate(null, "2026-07-20T00:00:00Z", null, NOW);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("no reason given"));
    }

    @Test
    void skipsExpiredRetentionWindow() {
        GovernanceGate.Decision d =
                GovernanceGate.evaluate("2026-07-01T00:00:00Z", null, null, NOW);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("expired"));
    }

    @Test
    void allowsWhenRetentionInFuture() {
        GovernanceGate.Decision d =
                GovernanceGate.evaluate("2026-12-31T00:00:00Z", null, null, NOW);
        assertTrue(d.allowed());
    }

    @Test
    void expiryIsInclusiveAtBoundary() {
        // now == valid_until means the window has closed → skipped
        GovernanceGate.Decision d =
                GovernanceGate.evaluate("2026-07-22T12:00:00Z", null, null, NOW);
        assertFalse(d.allowed());
    }

    @Test
    void forgetTakesPrecedenceOverValidRetention() {
        GovernanceGate.Decision d =
                GovernanceGate.evaluate("2026-12-31T00:00:00Z", "2026-07-21T00:00:00Z", "asked to forget", NOW);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("forgotten"));
    }

    @Test
    void failsClosedOnUnparseableRetention() {
        GovernanceGate.Decision d =
                GovernanceGate.evaluate("not-a-date", null, null, NOW);
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("unparseable"));
    }
}
