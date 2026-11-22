-- Where a decision's project came from (#97).
--   'configured': listed in KCP_MEMORY_DECISION_PROJECTS, or found through the legacy project.json source
--   'session'   : discovered from a session working directory, so its text is unverified
-- Existing rows default to 'session'. Every project is re-indexed on the next scan, which sets the real value.
ALTER TABLE decisions ADD COLUMN source TEXT NOT NULL DEFAULT 'session';
