-- kcp-memory v0.33.0 — memory governance
-- Turns indexed sessions into *governed* memory entries. Three pillars:
--   (a) retention  — an optional expiry (valid_until); NULL = never expires.
--   (b) provenance — which source produced the memory (session/tool descriptor).
--   (c) right-to-forget — an explicit tombstone (forgotten_at + forget_reason).
-- Recall (search/list) is gated on these: expired or forgotten memories are skipped.

-- (a) retention: ISO-8601 UTC expiry; NULL means the memory never expires.
ALTER TABLE sessions ADD COLUMN valid_until TEXT;
-- (b) provenance: origin descriptor of this memory (which source produced it).
ALTER TABLE sessions ADD COLUMN provenance TEXT;
-- (c) right-to-forget: ISO-8601 UTC tombstone; NULL means the memory is live.
ALTER TABLE sessions ADD COLUMN forgotten_at TEXT;
-- (c) right-to-forget: audit reason recorded with the forget.
ALTER TABLE sessions ADD COLUMN forget_reason TEXT;

-- Backfill provenance for pre-governance rows so every memory has a traceable origin.
UPDATE sessions
   SET provenance = 'claude-code:' || COALESCE(NULLIF(slug, ''), project_dir, '') || '#' || session_id
 WHERE provenance IS NULL OR provenance = '';

-- Indexes for the recall gate (skip forgotten / expired) and audit queries.
CREATE INDEX IF NOT EXISTS idx_sessions_forgotten ON sessions(forgotten_at);
CREATE INDEX IF NOT EXISTS idx_sessions_valid_until ON sessions(valid_until);
