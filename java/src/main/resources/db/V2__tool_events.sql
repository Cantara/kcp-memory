-- kcp-memory v0.2.0 — tool_events schema
-- Individual tool-call events ingested from ~/.kcp/events.jsonl (written by kcp-commands)

CREATE TABLE IF NOT EXISTS tool_events (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    event_ts     TEXT    NOT NULL,               -- ISO-8601 timestamp from the event
    session_id   TEXT    NOT NULL,
    project_dir  TEXT    NOT NULL,
    tool         TEXT    NOT NULL DEFAULT 'Bash',
    command      TEXT    NOT NULL,               -- first 500 chars of the command
    manifest_key TEXT,                           -- null if kcp-commands had no manifest
    ingested_at  TEXT    NOT NULL,               -- when kcp-memory ingested this event
    UNIQUE (event_ts, session_id, command)       -- dedup key
);

-- FTS5 index for fast full-text search over commands and project paths
CREATE VIRTUAL TABLE IF NOT EXISTS tool_events_fts USING fts5(
    command,
    project_dir,
    content='tool_events',
    content_rowid='id'
);

CREATE TRIGGER IF NOT EXISTS tool_events_ai AFTER INSERT ON tool_events BEGIN
    INSERT INTO tool_events_fts(rowid, command, project_dir)
    VALUES (new.id, new.command, new.project_dir);
END;

CREATE TRIGGER IF NOT EXISTS tool_events_ad AFTER DELETE ON tool_events BEGIN
    INSERT INTO tool_events_fts(tool_events_fts, rowid, command, project_dir)
    VALUES ('delete', old.id, old.command, old.project_dir);
END;

-- Track byte offset into ~/.kcp/events.jsonl for incremental ingestion
CREATE TABLE IF NOT EXISTS event_log_cursor (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    byte_offset INTEGER NOT NULL DEFAULT 0
);

INSERT OR IGNORE INTO event_log_cursor (id, byte_offset) VALUES (1, 0);

-- Indexes for common query patterns
CREATE INDEX IF NOT EXISTS idx_tool_events_session  ON tool_events(session_id);
CREATE INDEX IF NOT EXISTS idx_tool_events_project  ON tool_events(project_dir);
CREATE INDEX IF NOT EXISTS idx_tool_events_ts       ON tool_events(event_ts DESC);
CREATE INDEX IF NOT EXISTS idx_tool_events_manifest ON tool_events(manifest_key);
