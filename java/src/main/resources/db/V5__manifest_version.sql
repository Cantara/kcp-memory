-- kcp-memory v0.8.0 — add manifest_version to tool_events
-- SHA-256 first 8 chars of the manifest YAML active at invocation time.
-- NULL for events before v0.16.0 or when no manifest matched.
ALTER TABLE tool_events ADD COLUMN manifest_version TEXT;
CREATE INDEX idx_tool_events_manifest_version ON tool_events(manifest_version);
