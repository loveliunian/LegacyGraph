-- ============================================
-- V9: ChangeTask 变更闭环（增强版2）
-- 变更任务 / 补丁文件 / 验证门禁 / PR 任务
-- 见 doc §ChangeTask 落地模块、§PatchPlan 输出契约、§验证门禁与图谱回写
-- ============================================

-- 1. 变更任务表
CREATE TABLE IF NOT EXISTS lg_change_task (
    id                  UUID PRIMARY KEY,
    project_id          UUID NOT NULL,
    version_id          UUID,
    task_type           VARCHAR(32)  NOT NULL,          -- BUGFIX / REFACTOR / UPGRADE
    title               VARCHAR(255) NOT NULL,
    input_issue         JSONB,
    impacted_subgraph   JSONB,
    proposal            JSONB,
    risk_level          VARCHAR(16),                    -- LOW / MEDIUM / HIGH
    status              VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    agent_run_id        VARCHAR(64),
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_lg_change_task_project ON lg_change_task(project_id);
CREATE INDEX IF NOT EXISTS idx_lg_change_task_status  ON lg_change_task(status);
COMMENT ON TABLE lg_change_task IS '变更任务 - bugfix/refactor/upgrade 受控执行的状态机载体';

-- 2. 补丁文件表
CREATE TABLE IF NOT EXISTS lg_patch_file (
    id                  UUID PRIMARY KEY,
    change_task_id      UUID NOT NULL REFERENCES lg_change_task(id),
    file_path           TEXT         NOT NULL,
    change_type         VARCHAR(32)  NOT NULL,          -- CREATE / MODIFY / DELETE
    before_sha          VARCHAR(64),
    after_sha           VARCHAR(64),
    patch_text          TEXT         NOT NULL,
    generated_by        VARCHAR(64)  NOT NULL,
    evidence_ids        JSONB,
    status              VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_lg_patch_file_task ON lg_patch_file(change_task_id);
COMMENT ON TABLE lg_patch_file IS '补丁文件 - unified diff，落盘前需过范围/格式/证据三类校验';

-- 3. 验证门禁表
CREATE TABLE IF NOT EXISTS lg_validation_gate (
    id                  UUID PRIMARY KEY,
    change_task_id      UUID NOT NULL REFERENCES lg_change_task(id),
    gate_type           VARCHAR(32)  NOT NULL,          -- STATIC / UNIT / API / DB / E2E / MIGRATION
    command             TEXT,
    result              VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    report_uri          TEXT,
    started_at          TIMESTAMP,
    finished_at         TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_lg_validation_gate_task ON lg_validation_gate(change_task_id);
COMMENT ON TABLE lg_validation_gate IS '验证门禁 - 复用测试执行结果，不另造测试结果表';

-- 4. PR 任务表
CREATE TABLE IF NOT EXISTS lg_pr_task (
    id                  UUID PRIMARY KEY,
    change_task_id      UUID NOT NULL REFERENCES lg_change_task(id),
    branch_name         VARCHAR(255) NOT NULL,
    pr_url              TEXT,
    pr_status           VARCHAR(32)  NOT NULL DEFAULT 'NOT_CREATED',
    reviewer_policy     JSONB,
    rollback_plan       JSONB,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted             SMALLINT     NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_lg_pr_task_task ON lg_pr_task(change_task_id);
COMMENT ON TABLE lg_pr_task IS 'PR 任务 - AI 只创建 feature branch，未过门禁不能建 PR';
