package com.cantara.kcp.memory.model;

/**
 * Quality metrics for a single (manifest_key, manifest_version) pair.
 * Computed by ManifestQualityStore.analyzeByVersion().
 * manifestVersion is the 8-char SHA-256 content hash, or "unknown" for pre-v0.16.0 events.
 */
public record ManifestVersionRecord(
        String manifestKey,
        String manifestVersion,
        String firstSeen,
        String lastSeen,
        int    totalCalls,
        int    retryCount,
        int    helpFollowupCount,
        int    errorCount,
        double qualityScore
) {

    public double retryRate() {
        return totalCalls > 0 ? (double) retryCount / totalCalls : 0.0;
    }

    public double helpFollowupRate() {
        return totalCalls > 0 ? (double) helpFollowupCount / totalCalls : 0.0;
    }

    public double errorRate() {
        return totalCalls > 0 ? (double) errorCount / totalCalls : 0.0;
    }
}
