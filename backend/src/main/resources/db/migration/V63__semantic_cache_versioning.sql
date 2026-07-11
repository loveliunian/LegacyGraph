-- ============================================
-- V63: lg_semantic_cache 版本化缓存字段（Task 9.6 / 9.8）
-- --
-- graph_release_id  所属图谱发布ID，版本化缓存隔离维度
-- acl_hash          ACL 主体哈希，版本化缓存隔离维度
-- intent            生成答案时的意图分类
-- confidence        置信度分数（0.0~1.0）
-- --
-- 新增字段允许 NULL，保持与旧数据的向后兼容：
-- 旧缓存条目 graph_release_id/acl_hash 为 NULL，版本化查询时匹配 NULL 列。
-- ============================================

ALTER TABLE lg_semantic_cache
    ADD COLUMN IF NOT EXISTS graph_release_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS acl_hash         VARCHAR(128),
    ADD COLUMN IF NOT EXISTS intent           VARCHAR(64),
    ADD COLUMN IF NOT EXISTS confidence       DOUBLE PRECISION;

COMMENT ON COLUMN lg_semantic_cache.graph_release_id IS '所属图谱发布ID（版本化缓存维度，关联 lg_graph_release.id），NULL 表示无版本隔离';
COMMENT ON COLUMN lg_semantic_cache.acl_hash         IS 'ACL 主体哈希（版本化缓存维度），NULL 表示公开访问';
COMMENT ON COLUMN lg_semantic_cache.intent           IS '生成答案时的意图分类（FACT_LOOKUP/STRUCTURAL/.../CHANGE_IMPACT）';
COMMENT ON COLUMN lg_semantic_cache.confidence       IS '置信度分数（0.0~1.0），由 ConfidenceScorer 动态计算';

-- 版本化缓存查询索引：project + release + acl（最常用路径）
CREATE INDEX IF NOT EXISTS idx_semantic_cache_project_release_acl
    ON lg_semantic_cache(project_id, graph_release_id, acl_hash);

-- graph_release_id 单独索引（按发布版本失效缓存）
CREATE INDEX IF NOT EXISTS idx_semantic_cache_graph_release
    ON lg_semantic_cache(graph_release_id);

-- acl_hash 索引（按 ACL 上下文查询缓存）
CREATE INDEX IF NOT EXISTS idx_semantic_cache_acl_hash
    ON lg_semantic_cache(acl_hash);

-- intent 索引（按意图类型筛选缓存）
CREATE INDEX IF NOT EXISTS idx_semantic_cache_intent
    ON lg_semantic_cache(intent);
