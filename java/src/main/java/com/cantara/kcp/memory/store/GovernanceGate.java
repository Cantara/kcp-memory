package com.cantara.kcp.memory.store;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Temporal governance gate for recalled memory entries.
 *
 * <p>Mirrors the KCP planner's temporal gate: a memory that has been
 * <em>forgotten</em> (right-to-forget tombstone) or whose <em>retention
 * window</em> has expired is skipped from recall, and a written reason is
 * produced so the decision is auditable.
 *
 * <p>All timestamps are ISO-8601 UTC (e.g. {@code 2026-07-22T10:00:00Z}).
 * The gate is pure and side-effect free so it can be unit-tested in isolation
 * and reused by both the fast SQL recall path and the audit path.
 */
public final class GovernanceGate {

    private GovernanceGate() {}

    /**
     * A gate decision for one memory entry.
     *
     * @param allowed true if the memory may be surfaced by recall
     * @param reason  human-readable audit reason (null when allowed with no note)
     */
    public record Decision(boolean allowed, String reason) {
        public static Decision allow()              { return new Decision(true, null); }
        public static Decision skip(String reason)  { return new Decision(false, reason); }
    }

    /**
     * Evaluate the governance state of a single memory entry.
     *
     * @param validUntil  retention expiry (ISO-8601 UTC), or null for no expiry
     * @param forgottenAt tombstone timestamp (ISO-8601 UTC), or null if live
     * @param forgetReason optional reason recorded with the forget
     * @param now         the instant to evaluate against
     */
    public static Decision evaluate(String validUntil, String forgottenAt,
                                    String forgetReason, Instant now) {
        // Right-to-forget takes precedence — a forgotten memory is never surfaced.
        if (forgottenAt != null && !forgottenAt.isBlank()) {
            String why = (forgetReason != null && !forgetReason.isBlank())
                    ? forgetReason : "no reason given";
            return Decision.skip("forgotten at " + forgottenAt + " (" + why + ")");
        }

        // Retention window — skip once the entry is past its valid_until.
        if (validUntil != null && !validUntil.isBlank()) {
            Instant expiry;
            try {
                expiry = Instant.parse(validUntil.trim());
            } catch (DateTimeParseException e) {
                // A malformed retention window is treated as expired: fail closed
                // rather than surface a memory whose governance we cannot verify.
                return Decision.skip("unparseable retention window: " + validUntil);
            }
            if (!now.isBefore(expiry)) {
                return Decision.skip("retention window expired at " + validUntil
                        + " (now " + now + ")");
            }
        }

        return Decision.allow();
    }
}
