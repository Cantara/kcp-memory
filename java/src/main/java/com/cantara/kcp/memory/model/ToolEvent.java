package com.cantara.kcp.memory.model;

/**
 * A single tool-call event ingested from ~/.kcp/events.jsonl.
 * Written by kcp-commands on every Phase A Bash hook call.
 */
public record ToolEvent(
        long   id,
        String eventTs,
        String sessionId,
        String projectDir,
        String tool,
        String command,
        String manifestKey,   // null if kcp-commands had no manifest for this command
        String ingestedAt
) {}
