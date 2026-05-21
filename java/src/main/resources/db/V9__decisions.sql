-- kcp-memory v0.33.0 — Decision memory
-- Indexes architectural decisions, constraints, and anti-patterns from project .sdd/decisions/*.yaml

CREATE TABLE IF NOT EXISTS decisions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    decision_id     TEXT    NOT NULL,           -- kebab-case ID from YAML
    type            TEXT    NOT NULL,           -- decision | anti-pattern | constraint | workaround
    domain          TEXT    NOT NULL,           -- deployment | testing | video-build | etc.
    what            TEXT    NOT NULL,           -- one-sentence summary
    why             TEXT    NOT NULL,           -- reasoning/context
    alternatives    TEXT,                       -- JSON array of rejected alternatives
    learned         TEXT    NOT NULL,           -- session ID or skill reference
    updated         TEXT,                       -- session ID if revised (optional)
    tags            TEXT,                       -- JSON array of keywords
    project_path    TEXT    NOT NULL,           -- which project this came from
    file_path       TEXT    NOT NULL,           -- full path to source YAML file
    scanned_at      TEXT    NOT NULL,           -- when kcp-memory indexed this
    UNIQUE(decision_id, project_path)           -- same ID can exist in different projects
);

CREATE INDEX IF NOT EXISTS idx_decisions_type ON decisions(type);
CREATE INDEX IF NOT EXISTS idx_decisions_domain ON decisions(domain);
CREATE INDEX IF NOT EXISTS idx_decisions_project ON decisions(project_path);

-- FTS5 index over decision content for fast keyword search
CREATE VIRTUAL TABLE IF NOT EXISTS decisions_fts USING fts5(
    decision_id UNINDEXED,
    type,
    domain,
    what,
    why,
    tags,
    project_path UNINDEXED,
    content='decisions',
    content_rowid='id'
);

-- Keep FTS in sync with decisions table
CREATE TRIGGER IF NOT EXISTS decisions_ai AFTER INSERT ON decisions BEGIN
    INSERT INTO decisions_fts(rowid, decision_id, type, domain, what, why, tags, project_path)
    VALUES (new.id, new.decision_id, new.type, new.domain, new.what, new.why, new.tags, new.project_path);
END;

CREATE TRIGGER IF NOT EXISTS decisions_au AFTER UPDATE ON decisions BEGIN
    INSERT INTO decisions_fts(decisions_fts, rowid, decision_id, type, domain, what, why, tags, project_path)
    VALUES ('delete', old.id, old.decision_id, old.type, old.domain, old.what, old.why, old.tags, old.project_path);
    INSERT INTO decisions_fts(rowid, decision_id, type, domain, what, why, tags, project_path)
    VALUES (new.id, new.decision_id, new.type, new.domain, new.what, new.why, new.tags, new.project_path);
END;

CREATE TRIGGER IF NOT EXISTS decisions_ad AFTER DELETE ON decisions BEGIN
    INSERT INTO decisions_fts(decisions_fts, rowid, decision_id, type, domain, what, why, tags, project_path)
    VALUES ('delete', old.id, old.decision_id, old.type, old.domain, old.what, old.why, old.tags, old.project_path);
END;
