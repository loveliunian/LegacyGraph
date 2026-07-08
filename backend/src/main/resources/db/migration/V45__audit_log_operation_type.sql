-- 操作日志增加 operation_type 字段，H2/PG 均兼容
ALTER TABLE lg_sys_operation_log ADD COLUMN IF NOT EXISTS operation_type VARCHAR(20);
