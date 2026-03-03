-- kcp-memory v0.1.0 — SQLite schema
-- Episodic memory for Claude Code sessions

CREATE TABLE IF NOT EXISTS sessions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id      TEXT    NOT NULL UNIQUE,
    project_dir     TEXT    NOT NULL,
    git_branch      TEXT,
    slug            TEXT,                -- derived from ~/.claude/projects/<slug>/
    model           TEXT,
    started_at      TEXT,                -- ISO-8601
    ended_at        TEXT,                -- ISO-8601 (last turn timestamp)
    turn_count      INTEGER DEFAULT 0,
    tool_call_count INTEGER DEFAULT 0,
    tool_names      TEXT,                -- JSON array of distinct tool names used
    files_json      TEXT,                -- JSON array of file paths touched
    first_message   TEXT,               -- first human turn summary (≤500 chars)
    all_user_text   TEXT,               -- concatenated human turns (for FTS)
    scanned_at      TEXT NOT NULL        -- when kcp-memory last processed this file
);

CREATE TABLE IF NOT EXISTS tool_usages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id  TEXT    NOT NULL REFERENCES sessions(session_id),
    tool_name   TEXT    NOT NULL,
    tool_input  TEXT,                   -- JSON blob of tool input
    occurred_at TEXT                    -- ISO-8601 timestamp of the turn
);

-- FTS5 index over session content — enables fast full-text search
CREATE VIRTUAL TABLE IF NOT EXISTS sessions_fts USING fts5(
    session_id UNINDEXED,
    first_message,
    all_user_text,
    content='sessions',
    content_rowid='id'
);

-- Keep FTS in sync with sessions table
CREATE TRIGGER IF NOT EXISTS sessions_ai AFTER INSERT ON sessions BEGIN
    INSERT INTO sessions_fts(rowid, session_id, first_message, all_user_text)
    VALUES (new.id, new.session_id, new.first_message, new.all_user_text);
END;

CREATE TRIGGER IF NOT EXISTS sessions_au AFTER UPDATE ON sessions BEGIN
    INSERT INTO sessions_fts(sessions_fts, rowid, session_id, first_message, all_user_text)
    VALUES ('delete', old.id, old.session_id, old.first_message, old.all_user_text);
    INSERT INTO sessions_fts(rowid, session_id, first_message, all_user_text)
    VALUES (new.id, new.session_id, new.first_message, new.all_user_text);
END;

CREATE TRIGGER IF NOT EXISTS sessions_ad AFTER DELETE ON sessions BEGIN
    INSERT INTO sessions_fts(sessions_fts, rowid, session_id, first_message, all_user_text)
    VALUES ('delete', old.id, old.session_id, old.first_message, old.all_user_text);
END;

-- Index for common queries
CREATE INDEX IF NOT EXISTS idx_sessions_project ON sessions(project_dir);
CREATE INDEX IF NOT EXISTS idx_sessions_started ON sessions(started_at DESC);
CREATE INDEX IF NOT EXISTS idx_tool_usages_session ON tool_usages(session_id);
CREATE INDEX IF NOT EXISTS idx_tool_usages_tool ON tool_usages(tool_name);
