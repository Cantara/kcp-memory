-- V10: Session tags for multi-dimensional session classification
-- session_tags is a JSON array, e.g. '["Mynder","ExoCortex-CC","pr-919"]'
-- Tag filtering uses json_each() — no FTS change required.

ALTER TABLE sessions ADD COLUMN session_tags TEXT;

CREATE INDEX IF NOT EXISTS idx_sessions_has_tags ON sessions(id) WHERE session_tags IS NOT NULL;
