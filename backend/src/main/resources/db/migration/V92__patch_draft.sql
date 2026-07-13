-- ============================================
-- V92: 补丁草案中间层（阶段二-2.2）
--
-- PatchDraftService 将已审批的 Solution 转换为可验证的 PatchDraft，
-- 每个 Draft 包含文件级 diff、证据引用、风险评估。
-- 状态机：DRAFT → VALIDATED → MATERIALIZED → EXPIRED
-- ============================================

CREATE TABLE IF NOT EXISTS lg_patch_draft (
    id                    UUID PRIMARY KEY,
    solution_id           UUID NOT NULL,
    project_id            UUID NOT NULL,
    version_id            UUID,
    status                VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    files_json            TEXT,
    risk_level            VARCHAR(32),
    generated_by          VARCHAR(64),
    validation_report_json TEXT,
    created_at            TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    validated_at          TIMESTAMP,
    materialized_at       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_lg_patch_draft_solution ON lg_patch_draft(solution_id);
CREATE INDEX IF NOT EXISTS idx_lg_patch_draft_project  ON lg_patch_draft(project_id);
CREATE INDEX IF NOT EXISTS idx_lg_patch_draft_status   ON lg_patch_draft(status);

COMMENT ON TABLE lg_patch_draft IS '补丁草案 - Solution 与 ChangeTask/PatchPlan 之间的关键中间层';
COMMENT ON COLUMN lg_patch_draft.status IS 'DRAFT / VALIDATED / MATERIALIZED / EXPIRED';
COMMENT ON COLUMN lg_patch_draft.files_json IS '文件级变更列表（DraftFile JSON 数组）';
COMMENT ON COLUMN lg_patch_draft.risk_level IS 'LOW / MEDIUM / HIGH';
COMMENT ON COLUMN lg_patch_draft.generated_by IS '生成来源：llm / manual';
