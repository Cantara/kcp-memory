package com.cantara.kcp.memory.model;

/**
 * Aggregated quality metrics for a single manifest key.
 * Computed by ManifestQualityStore from tool_events data.
 */
public record ManifestQualityRecord(
        String manifestKey,
        int    totalCalls,
        int    retryCount,
        int    helpFollowupCount,
        int    errorCount,
        double qualityScore
) {

    /** Retry rate as a fraction 0.0–1.0. */
    public double retryRate() {
        return totalCalls > 0 ? (double) retryCount / totalCalls : 0.0;
    }

    /** Help-followup rate as a fraction 0.0–1.0. */
    public double helpFollowupRate() {
        return totalCalls > 0 ? (double) helpFollowupCount / totalCalls : 0.0;
    }

    /** Error rate as a fraction 0.0–1.0. */
    public double errorRate() {
        return totalCalls > 0 ? (double) errorCount / totalCalls : 0.0;
    }
}
