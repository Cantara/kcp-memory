package com.cantara.kcp.memory.scanner;

/**
 * How long to wait before parsing a changed transcript again (#105).
 * <p>
 * A changed file is parsed from the start, and the active session's transcript changes
 * on every tool call. For a transcript of several hundred MB that is seconds of work per
 * scan. The wait grows with the file size, so small files are still indexed at once and
 * a large one is re-indexed at most once per interval.
 *
 * @param minSizeBytes   files smaller than this are never delayed
 * @param bytesPerSecond one second of wait per this many bytes
 * @param maxIntervalMs  upper bound on the wait
 */
public record ReparseThrottle(long minSizeBytes, long bytesPerSecond, long maxIntervalMs) {

    /** 16 MB and up: one second per 8 MB, at most 60 s (so 100 MB waits 12.5 s, 480 MB and up 60 s). */
    public static final ReparseThrottle DEFAULT = new ReparseThrottle(16L << 20, 8L << 20, 60_000);

    /** Never delays. */
    public static final ReparseThrottle NONE = new ReparseThrottle(Long.MAX_VALUE, 1, 0);

    /** {@link #DEFAULT}, or {@link #NONE} when {@code KCP_MEMORY_REPARSE_THROTTLE=off}. */
    public static ReparseThrottle fromEnvironment() {
        return "off".equalsIgnoreCase(System.getenv("KCP_MEMORY_REPARSE_THROTTLE")) ? NONE : DEFAULT;
    }

    /** Minimum time between two parses of a file of this size; 0 means no wait. */
    public long intervalMs(long sizeBytes) {
        if (sizeBytes < minSizeBytes) return 0;
        return Math.min(maxIntervalMs, sizeBytes * 1000 / bytesPerSecond);
    }
}
