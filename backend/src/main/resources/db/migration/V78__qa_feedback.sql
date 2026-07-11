-- ============================================
-- V78: QA 声明反馈与方案评审差异
--
-- lg_qa_claim_feedback: 记录对图谱声明（claim）的 QA 反馈，
--   包括期望证据、反馈类型等，用于评估图谱质量。
-- lg_solution_review_diff: 记录方案评审过程中每一步的差异，
--   便于追溯方案修改历程。
--
-- 注意：lg_qa_feedback 表已由 V29（QA 对话反馈）创建，
--   此处声明级反馈使用 lg_qa_claim_feedback 避免冲突。
-- ============================================

-- 1. QA 声明反馈表
CREATE TABLE IF NOT EXISTS lg_qa_claim_feedback (
    id                  VARCHAR(40) PRIMARY KEY,
    project_id          VARCHAR(64),
    graph_release_id    VARCHAR(40),
    question_hash       VARCHAR(64),
    claim_text          TEXT,
    feedback_type       VARCHAR(20),
    expected_evidence   TEXT,
    principal           VARCHAR(64),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qa_claim_feedback_project ON lg_qa_claim_feedback(project_id, created_at);
CREATE INDEX IF NOT EXISTS idx_qa_claim_feedback_release ON lg_qa_claim_feedback(graph_release_id);
COMMENT ON TABLE lg_qa_claim_feedback IS 'QA 声明反馈 - 记录对图谱声明的评估反馈与期望证据';

-- 2. 方案评审差异表
CREATE TABLE IF NOT EXISTS lg_solution_review_diff (
    id                  VARCHAR(40) PRIMARY KEY,
    solution_id         VARCHAR(40),
    reviewer            VARCHAR(64),
    step_index          INTEGER,
    diff_type           VARCHAR(20),
    before_summary      TEXT,
    after_summary       TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_solution_review_diff_solution ON lg_solution_review_diff(solution_id, step_index);
COMMENT ON TABLE lg_solution_review_diff IS '方案评审差异 - 记录方案评审过程中每一步的修改差异';
