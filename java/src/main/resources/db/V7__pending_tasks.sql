-- V7: Pending task queue for peer dispatch
-- Peers poll for tasks queued by the hub (or mobile app),
-- execute locally, and push results back.

CREATE TABLE IF NOT EXISTS pending_tasks (
    id           TEXT    PRIMARY KEY,
    peer_id      TEXT    NOT NULL,
    prompt       TEXT    NOT NULL,
    status       TEXT    NOT NULL DEFAULT 'queued',  -- queued | claimed | done | error
    created_at   TEXT    NOT NULL,
    claimed_at   TEXT,
    completed_at TEXT,
    result       TEXT,
    error        TEXT
);

CREATE INDEX IF NOT EXISTS idx_pending_tasks_peer_status
    ON pending_tasks(peer_id, status);
