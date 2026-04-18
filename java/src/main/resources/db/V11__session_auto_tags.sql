-- V11: Auto-tagging infrastructure
-- 1. Fix V10 index bug: sessions(id) does not exist; correct column is session_tags.
-- 2. Add pending_tags table to bridge timing gap between UserPromptSubmit hook and scanner.
-- 3. Add session_tags to agent_sessions for subagent tag inheritance.

DROP INDEX IF EXISTS idx_sessions_has_tags;
CREATE INDEX IF NOT EXISTS idx_sessions_has_tags ON sessions(session_tags) WHERE session_tags IS NOT NULL;

CREATE TABLE IF NOT EXISTS pending_tags (
    session_id TEXT PRIMARY KEY,
    tags       TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

ALTER TABLE agent_sessions ADD COLUMN session_tags TEXT;
