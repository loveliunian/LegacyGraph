-- V57: AI 扫描任务增量上下文字段 — 传递变更文件路径和受影响节点 ID 到 AI 编排阶段
ALTER TABLE lg_ai_scan_job ADD COLUMN IF NOT EXISTS changed_file_paths_json JSONB;
ALTER TABLE lg_ai_scan_job ADD COLUMN IF NOT EXISTS affected_node_ids_json JSONB;
