-- ============================================
-- V65: 方案生成与校验（Task 10）
--
-- lg_solution       方案主表（LLM 生成的文件级实施方案）
-- lg_solution_step  方案步骤（每个步骤对应一个文件级实施动作）
--
-- 状态流转：
--   DRAFT → READY_FOR_REVIEW（校验通过）
--         → NEEDS_INPUT（校验失败）
--   READY_FOR_REVIEW / NEEDS_INPUT → APPROVED / REJECTED（人工评审）
-- ============================================

-- 1. 方案主表
CREATE TABLE IF NOT EXISTS lg_solution (
    id                    UUID PRIMARY KEY,
    project_id            UUID NOT NULL,
    requirement_id        UUID NOT NULL REFERENCES lg_requirement(id) ON DELETE CASCADE,
    status                VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    summary               TEXT,
    -- 生成方案时的需求分析 / 影响子图 JSON 快照，verify 阶段重建校验上下文用
    analysis_json         TEXT,
    impact_result_json    TEXT,
    -- 校验错误信息（NEEDS_INPUT 时非空，JSON 数组字符串）
    verification_errors   TEXT,
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lg_solution_project   ON lg_solution(project_id);
CREATE INDEX IF NOT EXISTS idx_lg_solution_requirement ON lg_solution(requirement_id);
CREATE INDEX IF NOT EXISTS idx_lg_solution_status    ON lg_solution(status);
COMMENT ON TABLE lg_solution IS '方案主表 - LLM 生成的文件级实施方案，关联需求与影响子图';
COMMENT ON COLUMN lg_solution.status IS 'DRAFT / READY_FOR_REVIEW / NEEDS_INPUT / APPROVED / REJECTED';

-- 2. 方案步骤表
CREATE TABLE IF NOT EXISTS lg_solution_step (
    id                   UUID PRIMARY KEY,
    solution_id          UUID NOT NULL REFERENCES lg_solution(id) ON DELETE CASCADE,
    step_index           INTEGER NOT NULL,
    title                VARCHAR(255),
    description          TEXT,
    file_path            TEXT,
    symbol_name          VARCHAR(512),
    -- 证据 ID 列表（JSON 数组字符串，如 ["ev-001","ev-002"]）
    evidence_ids         TEXT,
    -- 动作类型：CREATE / MODIFY / DELETE
    action_type          VARCHAR(32),
    test_description     TEXT,
    rollback_description TEXT,
    created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lg_solution_step_solution ON lg_solution_step(solution_id);
CREATE INDEX IF NOT EXISTS idx_lg_solution_step_index    ON lg_solution_step(solution_id, step_index);
COMMENT ON TABLE lg_solution_step IS '方案步骤 - 每个步骤对应一个文件级实施动作（CREATE/MODIFY/DELETE）';
COMMENT ON COLUMN lg_solution_step.action_type IS 'CREATE / MODIFY / DELETE';
