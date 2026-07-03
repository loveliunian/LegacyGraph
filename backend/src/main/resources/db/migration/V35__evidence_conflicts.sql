CREATE TABLE IF NOT EXISTS lg_evidence_conflict (
    id            VARCHAR(64) PRIMARY KEY,
    project_id    VARCHAR(64) NOT NULL,
    title         VARCHAR(255) NOT NULL,
    severity      VARCHAR(32) NOT NULL,
    node_id       VARCHAR(128),
    source_a      JSONB,
    source_b      JSONB,
    ai_suggestion TEXT,
    context       TEXT,
    resolved      BOOLEAN NOT NULL DEFAULT FALSE,
    resolution    VARCHAR(32),
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at   TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_lg_evidence_conflict_project
    ON lg_evidence_conflict(project_id, resolved, created_at DESC);
