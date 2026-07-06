-- V38: 数据库连接增加 source 字段，区分手动配置与自动发现
ALTER TABLE lg_db_connection ADD COLUMN IF NOT EXISTS source VARCHAR(32) DEFAULT 'MANUAL';

-- 将已有的自动发现连接标记为 AUTO_DISCOVERED（由 auto-discovery 创建的）
UPDATE lg_db_connection SET source = 'AUTO_DISCOVERED' WHERE created_by = 'auto-discovery' AND source IS NULL;

COMMENT ON COLUMN lg_db_connection.source IS '连接来源：MANUAL（手动配置）/ AUTO_DISCOVERED（自动发现）';
