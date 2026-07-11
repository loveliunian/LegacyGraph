-- ============================================
-- V71: ChangeTask 指派与领取
--
-- 为变更任务增加 assignee / claimed_at / due_at 等字段，
-- 支持任务指派、领取、待办列表。
-- ============================================

ALTER TABLE lg_change_task ADD COLUMN IF NOT EXISTS assignee VARCHAR(64);
ALTER TABLE lg_change_task ADD COLUMN IF NOT EXISTS assignee_type VARCHAR(16);
ALTER TABLE lg_change_task ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMP;
ALTER TABLE lg_change_task ADD COLUMN IF NOT EXISTS due_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_change_task_assignee
    ON lg_change_task(project_id, assignee, status);

COMMENT ON COLUMN lg_change_task.assignee IS '指派给的用户/团队/角色标识';
COMMENT ON COLUMN lg_change_task.assignee_type IS '指派类型：USER / TEAM / ROLE';
COMMENT ON COLUMN lg_change_task.claimed_at IS '领取时间（用户实际接手任务的时间）';
COMMENT ON COLUMN lg_change_task.due_at IS '截止时间';
