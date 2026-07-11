-- ============================================
-- V73: 方案审批审计与评审字段
--
-- 为 Solution 增加审批人/评审时间字段，
-- 新增 lg_solution_audit 审计表记录每次状态变更。
-- ============================================

-- 1. 方案审批审计表
CREATE TABLE IF NOT EXISTS lg_solution_audit (
    id              VARCHAR(40) PRIMARY KEY,
    solution_id     VARCHAR(40) NOT NULL,
    reviewer        VARCHAR(64),
    before_status   VARCHAR(32) NOT NULL,
    after_status    VARCHAR(32) NOT NULL,
    decision        VARCHAR(32),
    comment         TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_solution_audit_solution ON lg_solution_audit(solution_id);
COMMENT ON TABLE lg_solution_audit IS '方案审批审计 - 记录方案每次状态变更与评审意见';

-- 2. 方案主表补充评审字段
ALTER TABLE lg_solution ADD COLUMN IF NOT EXISTS reviewer VARCHAR(64);
ALTER TABLE lg_solution ADD COLUMN IF NOT EXISTS review_comment TEXT;
ALTER TABLE lg_solution ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP;

COMMENT ON COLUMN lg_solution.reviewer IS '最终评审人';
COMMENT ON COLUMN lg_solution.review_comment IS '评审意见';
COMMENT ON COLUMN lg_solution.reviewed_at IS '最终评审时间';
