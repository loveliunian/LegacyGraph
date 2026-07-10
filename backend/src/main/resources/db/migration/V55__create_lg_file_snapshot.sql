-- V55__create_lg_file_snapshot.sql
-- 文件快照表：记录文件内容 SHA-256 哈希，支持基于内容哈希的增量扫描。
-- 重扫时对比哈希，仅对内容变更的文件重新执行抽取，避免重复处理未变更文件。

CREATE TABLE IF NOT EXISTS lg_file_snapshot (
    id          BIGSERIAL    PRIMARY KEY,
    project_id  VARCHAR(64)  NOT NULL,
    file_path   VARCHAR(1024) NOT NULL,           -- 文件相对路径（相对于项目根目录）
    file_hash   VARCHAR(64)  NOT NULL,            -- 内容 SHA-256 哈希（十六进制）
    file_size   BIGINT,                           -- 文件大小（字节）
    scanned_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, file_path)
);

CREATE INDEX IF NOT EXISTS idx_file_snapshot_project
    ON lg_file_snapshot(project_id);
