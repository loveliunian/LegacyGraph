-- V30: 语义缓存表
-- 用于 SemanticCache 服务，基于问题 embedding 实现语义相似度缓存
-- pgvector 扩展可能未安装，question_embedding 先以 TEXT 建列，再尝试转换

CREATE TABLE IF NOT EXISTS lg_semantic_cache (
    id VARCHAR(64) PRIMARY KEY,
    project_id VARCHAR(64) NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    evidence TEXT,
    question_embedding TEXT,
    hit_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_access_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_semantic_cache_project_id ON lg_semantic_cache(project_id);
CREATE INDEX IF NOT EXISTS idx_semantic_cache_last_access ON lg_semantic_cache(last_access_at);

-- 尝试转为 pgvector 类型（扩展不可用时保持 TEXT）
DO $$
BEGIN
    ALTER TABLE lg_semantic_cache ALTER COLUMN question_embedding TYPE vector(1024) USING question_embedding::vector(1024);
    CREATE INDEX IF NOT EXISTS idx_semantic_cache_embedding
        ON lg_semantic_cache USING ivfflat (question_embedding vector_cosine_ops)
        WITH (lists = 100);
    RAISE NOTICE 'pgvector question_embedding 列及 ivfflat 索引创建成功';
EXCEPTION WHEN OTHERS THEN
    RAISE NOTICE 'pgvector 扩展未安装，question_embedding 列保持 TEXT 类型: %', SQLERRM;
END $$;

COMMENT ON TABLE lg_semantic_cache IS '语义缓存表 - 基于问题 embedding 的问答缓存';
COMMENT ON COLUMN lg_semantic_cache.question_embedding IS '问题 embedding 向量（1024维，bge-m3）或 TEXT 降级';
