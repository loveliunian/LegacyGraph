-- ============================================
-- V6: 隐私分层（4.4）+ AgentRun 合约（4.3）
-- ============================================

-- 1. 证据隐私分层（doc 4.4）：把脱敏前移到证据层
ALTER TABLE lg_evidence ADD COLUMN IF NOT EXISTS privacy_level     VARCHAR(20) NOT NULL DEFAULT 'INTERNAL';
ALTER TABLE lg_evidence ADD COLUMN IF NOT EXISTS redaction_policy  VARCHAR(20) NOT NULL DEFAULT 'none';
COMMENT ON COLUMN lg_evidence.privacy_level    IS '隐私级别 PUBLIC/INTERNAL/CONFIDENTIAL/SECRET，SECRET 禁止送入外部 LLM';
COMMENT ON COLUMN lg_evidence.redaction_policy IS '脱敏策略 none/mask/hash/drop';

-- 2. AgentRun 合约表（doc 4.3）：让 AI 调用可评估/可回放/可比较
CREATE TABLE IF NOT EXISTS lg_agent_run (
    id                       BIGSERIAL PRIMARY KEY,
    contract_id              VARCHAR(64)  NOT NULL UNIQUE,
    project_id               UUID,
    agent_type               VARCHAR(64)  NOT NULL,
    agent_name               VARCHAR(128),
    input_schema_version     VARCHAR(30),
    output_schema_version    VARCHAR(30),
    used_evidence_ids        JSONB,
    omitted_because          JSONB,
    needs_human_review       SMALLINT     NOT NULL DEFAULT 0,
    model                    VARCHAR(100),
    prompt_tokens            INT,
    completion_tokens        INT,
    cost_usd                 DOUBLE PRECISION,
    retry_count              INT          NOT NULL DEFAULT 0,
    self_correction_count    INT          NOT NULL DEFAULT 0,
    quality_score            DOUBLE PRECISION,
    prompt_run_id            BIGINT       REFERENCES lg_prompt_run(id),
    started_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at              TIMESTAMP,
    metadata                 JSONB,
    created_at               TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_agent_run_project   ON lg_agent_run(project_id);
CREATE INDEX idx_lg_agent_run_agent     ON lg_agent_run(agent_type);
CREATE INDEX idx_lg_agent_run_prompt    ON lg_agent_run(prompt_run_id);

COMMENT ON TABLE lg_agent_run IS 'AgentRun 合约 - 每次Agent调用的schema版本/证据/成本/质量/自校正记录';
