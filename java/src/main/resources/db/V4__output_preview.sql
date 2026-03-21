-- kcp-memory v0.6.0 — add output_preview to tool_events
-- Stores the first 200 chars of tool output, captured by the kcp-memory PostToolUse hook.
-- NULL means the event predates output capture or no output was produced.

ALTER TABLE tool_events ADD COLUMN output_preview TEXT;
