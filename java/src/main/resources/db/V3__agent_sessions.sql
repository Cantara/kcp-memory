-- kcp-memory v0.5.0 — agent (subagent) sessions schema
-- Indexes Claude Code subagent transcripts from ~/.claude/projects/<project>/<session>/subagents/

CREATE TABLE IF NOT EXISTS agent_sessions (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    agent_id          TEXT    NOT NULL UNIQUE,    -- e.g. "ab5a2b081b1711482"
    parent_session_id TEXT    NOT NULL,           -- the parent session UUID
    agent_slug        TEXT,                       -- human-readable name, e.g. "breezy-sauteeing-hearth"
    project_dir       TEXT,                       -- cwd from the agent's messages
    cwd               TEXT,                       -- working directory
    model             TEXT,                       -- model used by the agent
    turn_count        INTEGER DEFAULT 0,
    tool_call_count   INTEGER DEFAULT 0,
    tool_names        TEXT,                       -- JSON array of distinct tool names
    first_message     TEXT,                       -- first user turn (<=500 chars)
    all_user_text     TEXT,                       -- concatenated user turns (for FTS)
    first_seen_at     TEXT,                       -- ISO-8601 timestamp of first message
    last_updated_at   TEXT,                       -- ISO-8601 timestamp of last message
    message_count     INTEGER DEFAULT 0,          -- total JSONL lines
    scanned_at        TEXT    NOT NULL            -- when kcp-memory last processed this file
    -- no FK on parent_session_id: parent sessions may not be indexed (advisory relationship)
);

-- FTS5 index for searching agent session content
CREATE VIRTUAL TABLE IF NOT EXISTS agent_sessions_fts USING fts5(
    agent_id UNINDEXED,
    agent_slug,
    first_message,
    all_user_text,
    content='agent_sessions',
    content_rowid='id'
);

-- Keep FTS in sync with agent_sessions table
CREATE TRIGGER IF NOT EXISTS agent_sessions_ai AFTER INSERT ON agent_sessions BEGIN
    INSERT INTO agent_sessions_fts(rowid, agent_id, agent_slug, first_message, all_user_text)
    VALUES (new.id, new.agent_id, new.agent_slug, new.first_message, new.all_user_text);
END;

CREATE TRIGGER IF NOT EXISTS agent_sessions_au AFTER UPDATE ON agent_sessions BEGIN
    INSERT INTO agent_sessions_fts(agent_sessions_fts, rowid, agent_id, agent_slug, first_message, all_user_text)
    VALUES ('delete', old.id, old.agent_id, old.agent_slug, old.first_message, old.all_user_text);
    INSERT INTO agent_sessions_fts(rowid, agent_id, agent_slug, first_message, all_user_text)
    VALUES (new.id, new.agent_id, new.agent_slug, new.first_message, new.all_user_text);
END;

CREATE TRIGGER IF NOT EXISTS agent_sessions_ad AFTER DELETE ON agent_sessions BEGIN
    INSERT INTO agent_sessions_fts(agent_sessions_fts, rowid, agent_id, agent_slug, first_message, all_user_text)
    VALUES ('delete', old.id, old.agent_id, old.agent_slug, old.first_message, old.all_user_text);
END;

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_agent_sessions_parent  ON agent_sessions(parent_session_id);
CREATE INDEX IF NOT EXISTS idx_agent_sessions_project ON agent_sessions(project_dir);
CREATE INDEX IF NOT EXISTS idx_agent_sessions_slug    ON agent_sessions(agent_slug);
