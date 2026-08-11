package com.cantara.kcp.memory.model;

import java.util.List;

/**
 * A recurring command pattern mined from tool_events — the same command appearing
 * across multiple distinct sessions, a candidate for extraction into a reusable
 * skill manifest via {@code kcp-memory suggest-skill}.
 */
public record CommandPattern(
        String command,
        int occurrenceCount,
        int sessionCount,
        List<String> sessionIds,
        String firstSeen,
        String lastSeen
) {}
