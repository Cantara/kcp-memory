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
        String manifestKey,       // null if kcp-commands had no manifest for this command
        String outputPreview,     // first 200 chars of tool output; null if not captured (v0.6.0)
        String manifestVersion,   // SHA-256 first 8 hex chars of the active manifest YAML (v0.8.0); null for older events
        String ingestedAt
) {}
