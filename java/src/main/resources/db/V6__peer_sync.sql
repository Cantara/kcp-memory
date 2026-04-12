-- V6: Peer synchronization support
-- Adds source-instance tracking, event deduplication hashes,
-- replication cursors, and a peer ingest endpoint table.

-- Tag existing records as local
ALTER TABLE sessions ADD COLUMN source_instance TEXT NOT NULL DEFAULT 'local';
ALTER TABLE tool_events ADD COLUMN source_instance TEXT NOT NULL DEFAULT 'local';
ALTER TABLE agent_sessions ADD COLUMN source_instance TEXT NOT NULL DEFAULT 'local';

-- Event deduplication hash — prevents duplicates in transitive sync (hub-and-spoke).
-- SHA-256 of (timestamp + tool + command + session_id). Globally unique.
ALTER TABLE tool_events ADD COLUMN event_hash TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_events_hash ON tool_events(event_hash);

-- Replication cursor per peer — tracks how far we've synced in each direction
CREATE TABLE IF NOT EXISTS peer_cursors (
    peer_id            TEXT PRIMARY KEY,
    last_session_ts    TEXT,           -- ISO-8601: last session pulled from this peer
    last_event_ts      TEXT,           -- ISO-8601: last event pulled from this peer
    last_agent_ts      TEXT,           -- ISO-8601: last agent session pulled from this peer
    last_push_session_ts TEXT,         -- ISO-8601: last local session pushed to this peer
    last_push_event_ts   TEXT,         -- ISO-8601: last local event pushed to this peer
    updated_at         TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Index for efficient filtering by source
CREATE INDEX IF NOT EXISTS idx_sessions_source ON sessions(source_instance);
CREATE INDEX IF NOT EXISTS idx_events_source ON tool_events(source_instance);
CREATE INDEX IF NOT EXISTS idx_agent_sessions_source ON agent_sessions(source_instance);
