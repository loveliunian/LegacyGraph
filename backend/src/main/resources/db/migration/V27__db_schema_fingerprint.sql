-- V27: 数据库连接增加 schema fingerprint 字段，支持增量扫描跳过
ALTER TABLE IF EXISTS lg_db_connection
    ADD COLUMN IF NOT EXISTS schema_fingerprint VARCHAR(128),
    ADD COLUMN IF NOT EXISTS schema_fingerprint_updated_at TIMESTAMP;
