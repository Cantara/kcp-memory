-- kcp-memory v0.38.0 — navigation trajectory log
-- Backs kcp_memory_ls / kcp_memory_tree / kcp_memory_read: every call to one
-- of those tools appends a row here so a bad retrieval result is diagnosable
-- later ("why did the agent end up looking at X"). Write-mostly; read back via
-- kcp_memory_nav_history. session_id is best-effort caller-supplied
-- correlation (the MCP stdio protocol as implemented here does not carry the
-- calling Claude Code session id — the same gap already exists, unpopulated,
-- in usage_events.session_id in ~/.kcp/usage.db) — it is NULL unless the
-- caller passes one explicitly.

CREATE TABLE IF NOT EXISTS nav_trajectory (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ts           TEXT    NOT NULL,               -- ISO-8601 UTC timestamp of the call
    tool         TEXT    NOT NULL,               -- ls | tree | read
    path         TEXT    NOT NULL,               -- virtual path requested, e.g. /sessions/ab12cd34
    session_id   TEXT,                           -- optional caller-supplied correlation id
    summary      TEXT    NOT NULL                -- brief result summary, e.g. "returned 12 entries"
);

CREATE INDEX IF NOT EXISTS idx_nav_trajectory_ts      ON nav_trajectory(ts);
CREATE INDEX IF NOT EXISTS idx_nav_trajectory_tool    ON nav_trajectory(tool);
CREATE INDEX IF NOT EXISTS idx_nav_trajectory_session ON nav_trajectory(session_id);
