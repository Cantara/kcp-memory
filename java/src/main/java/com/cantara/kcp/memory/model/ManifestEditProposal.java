package com.cantara.kcp.memory.model;

import java.util.List;

/**
 * An evidence-backed proposal to review a skill/manifest — mined from the same
 * retry/help-followup/error signals {@link com.cantara.kcp.memory.store.ManifestQualityStore}
 * already tracks, but with per-session evidence attached.
 * <p>
 * Deliberately inert: a candidate for human review, never an automatic edit.
 * See kcp-memory#64 (this mining half) and knowledge-context-protocol#199
 * (the review/versioning half — a human-accepted proposal becomes a new signed
 * manifest version through KCP's existing versioning, same as any other change).
 */
public record ManifestEditProposal(
        String manifestKey,
        String reason,
        double qualityScore,
        int totalCalls,
        int totalSessions,
        int affectedSessions,
        int retrySessions,
        int helpSessions,
        int errorSessions,
        List<String> evidenceSessionIds,
        String generatedAt
) {}
