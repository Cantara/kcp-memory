-- kcp-memory — per-file scan state (#88)
-- One row per (transcript file, scanner) that the scanner has parsed, with the size
-- and mtime the file had when it was parsed. The next scan compares the file's current
-- size/mtime against this row BEFORE opening the file, and skips the parse when both
-- are unchanged. Keyed by (path, scanner) so the session and agent scanners never
-- overwrite each other's row.
-- Before this table the up-to-date check needed the session id, which only came out
-- of a full parse, so every scan read and parsed every transcript.
--
-- Additive only: no existing table or column changes, and older binaries ignore it.

CREATE TABLE IF NOT EXISTS scan_file_state (
    path         TEXT    NOT NULL,               -- absolute path of the transcript file
    scanner      TEXT    NOT NULL,               -- 'session' | 'agent'
    entity_id    TEXT    NOT NULL,               -- sessions.session_id / agent_sessions.agent_id ('' if the file produced nothing)
    size_bytes   INTEGER NOT NULL,               -- file size when parsed
    mtime_ms     INTEGER NOT NULL,               -- file last-modified (epoch ms) when parsed
    recorded_at  TEXT    NOT NULL,               -- ISO-8601 UTC time the row was written
    PRIMARY KEY (path, scanner)
);
