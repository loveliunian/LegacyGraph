-- V14__change_task_version_column.sql
-- Phase 0-2: 为 lg_change_task 添加 version 列（optimistic lock）
-- 防止重复点击导致并发推进

ALTER TABLE lg_change_task
    ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN lg_change_task.version IS '乐观锁版本号，每次状态迁移 +1';
