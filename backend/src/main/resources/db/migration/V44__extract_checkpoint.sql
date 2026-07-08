-- 扫描提取断点续传表
-- 记录每个文件在 AI 提取阶段的状态（PENDING/EXTRACTING/DONE/FAILED）
-- 大项目中断后可从断点恢复，避免重复 LLM 调用
CREATE TABLE IF NOT EXISTS lg_extract_checkpoint (
    id          BIGSERIAL PRIMARY KEY,
    project_id  VARCHAR(64)  NOT NULL,
    version_id  VARCHAR(64)  NOT NULL,
    file_path   VARCHAR(1024) NOT NULL,         -- 文件相对路径
    step_name   VARCHAR(64)  NOT NULL DEFAULT 'DOC_EXTRACT', -- 提取步骤（DOC_EXTRACT/CODE_EXTRACT）
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',    -- PENDING/EXTRACTING/DONE/FAILED
    result_json TEXT,                             -- 提取结果摘要（可选）
    error_msg   TEXT,                             -- 失败原因
    extracted_at TIMESTAMP,                       -- 提取完成时间
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, file_path, step_name)
);

CREATE INDEX IF NOT EXISTS idx_lg_extract_ckpt_lookup
    ON lg_extract_checkpoint(project_id, version_id, step_name, status);
