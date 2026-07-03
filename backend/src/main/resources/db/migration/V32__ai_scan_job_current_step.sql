-- AI 扫描任务状态机字段
ALTER TABLE lg_ai_scan_job ADD COLUMN IF NOT EXISTS current_step VARCHAR(50);
