-- ============================================
-- V62: lg_vector_document 新增 GraphRelease + ACL + 状态字段（Task 8.2 / 8.5）
-- --
-- graph_release_id  关联 lg_graph_release.id，支持按发布版本过滤检索
-- acl_principals    ACL 主体 JSON 数组，空表示无访问限制
-- document_status   文档状态：PUBLISHED / DRAFT
-- ============================================

ALTER TABLE lg_vector_document
    ADD COLUMN IF NOT EXISTS graph_release_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS acl_principals   TEXT,
    ADD COLUMN IF NOT EXISTS document_status  VARCHAR(32) DEFAULT 'PUBLISHED';

COMMENT ON COLUMN lg_vector_document.graph_release_id IS '所属图谱发布ID（关联 lg_graph_release.id）';
COMMENT ON COLUMN lg_vector_document.acl_principals   IS 'ACL 主体列表（JSON 数组，如 ["user:alice","group:dev"]），NULL 表示无限制';
COMMENT ON COLUMN lg_vector_document.document_status  IS '文档状态：PUBLISHED / DRAFT';

-- graph_release_id 过滤索引（Task 8.3 按 release 过滤场景）
CREATE INDEX IF NOT EXISTS idx_vector_doc_graph_release
    ON lg_vector_document(graph_release_id);

-- 复合索引：project + release（按发布版本检索最常用路径）
CREATE INDEX IF NOT EXISTS idx_vector_doc_project_release
    ON lg_vector_document(project_id, graph_release_id);

-- document_status 过滤索引
CREATE INDEX IF NOT EXISTS idx_vector_doc_status
    ON lg_vector_document(document_status);
