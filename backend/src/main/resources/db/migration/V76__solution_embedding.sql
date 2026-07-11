-- ============================================
-- V76: 方案 embedding 表（G-15 相似历史方案检索）
--
-- 存储 APPROVED 方案的嵌入文本与向量，
-- 供 SolutionSimilarityService 做相似历史方案检索。
-- ============================================

CREATE TABLE IF NOT EXISTS lg_solution_embedding (
    id              VARCHAR(40) PRIMARY KEY,
    solution_id     VARCHAR(40) NOT NULL,
    project_id      VARCHAR(64) NOT NULL,
    embedding_text  TEXT,
    embedding       BYTEA,
    useful_count    INTEGER DEFAULT 0,
    status          VARCHAR(16) DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_solution_embedding_project ON lg_solution_embedding(project_id, status);
COMMENT ON TABLE lg_solution_embedding IS '方案嵌入索引 - 用于相似历史方案检索';
