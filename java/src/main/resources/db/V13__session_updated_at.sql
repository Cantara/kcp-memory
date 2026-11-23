-- When a session row last changed (#85 follow-up, peer sync).
--
-- Peer sync listed sessions by started_at and inserted them with INSERT OR IGNORE, so a
-- session was copied once, at the turn and tool counts it had the first time a peer saw it,
-- and never refreshed. This column is the change marker the sync pages through: every write
-- to a session (scan, peer insert, peer refresh) sets it, and a peer asks for the sessions
-- that changed after a cursor of (updated_at, session_id).
--
-- It is separate from scanned_at on purpose: the local scanner reads scanned_at to decide
-- whether a transcript file is already indexed, and a refresh from a peer must not change that.
--
-- Canonical form: ISO-8601 UTC with exactly three fractional digits, so plain string
-- comparison orders correctly. NULL (a row written by something that does not set it) sorts first.
ALTER TABLE sessions ADD COLUMN updated_at TEXT;

UPDATE sessions
   SET updated_at = COALESCE(strftime('%Y-%m-%dT%H:%M:%fZ', COALESCE(scanned_at, started_at)),
                             '1970-01-01T00:00:00.000Z');
