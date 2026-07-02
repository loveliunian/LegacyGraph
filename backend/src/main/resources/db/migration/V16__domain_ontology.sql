-- V16: DomainOntology 领域本体数据模型
-- 目标：为领域术语提供结构化本体管理，支持同义词映射、上下位关系及禁止合并标记。
-- 两张表：lg_domain_ontology_term（术语表）和 lg_domain_ontology_relation（术语关系表）。

-- ============================================================
-- 1. 领域本体术语表
-- ============================================================
CREATE TABLE IF NOT EXISTS lg_domain_ontology_term (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    term VARCHAR(255) NOT NULL,
    canonical_term VARCHAR(255),
    aliases JSONB NOT NULL DEFAULT '[]'::JSONB,
    category VARCHAR(64),
    source VARCHAR(32),
    reviewed BOOLEAN NOT NULL DEFAULT false,
    deleted SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, term)
);

COMMENT ON TABLE lg_domain_ontology_term IS '领域本体术语表';
COMMENT ON COLUMN lg_domain_ontology_term.id IS '术语主键 UUID';
COMMENT ON COLUMN lg_domain_ontology_term.project_id IS '所属项目 UUID';
COMMENT ON COLUMN lg_domain_ontology_term.term IS '术语原文';
COMMENT ON COLUMN lg_domain_ontology_term.canonical_term IS '规范术语（标准化后的首选名称）';
COMMENT ON COLUMN lg_domain_ontology_term.aliases IS '别名列表 JSONB 数组';
COMMENT ON COLUMN lg_domain_ontology_term.category IS '术语分类：entity/attribute/relation/action/event 等';
COMMENT ON COLUMN lg_domain_ontology_term.source IS '来源：CODE_DOC/PROJECT_DOC/DB/MANUAL/AI';
COMMENT ON COLUMN lg_domain_ontology_term.reviewed IS '是否已人工审核';

CREATE INDEX IF NOT EXISTS idx_lg_domain_ontology_term_project
    ON lg_domain_ontology_term(project_id);
CREATE INDEX IF NOT EXISTS idx_lg_domain_ontology_term_category
    ON lg_domain_ontology_term(project_id, category);
CREATE INDEX IF NOT EXISTS idx_lg_domain_ontology_term_aliases_gin
    ON lg_domain_ontology_term USING GIN(aliases);

-- ============================================================
-- 2. 领域本体术语关系表
-- ============================================================
CREATE TABLE IF NOT EXISTS lg_domain_ontology_relation (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    from_term_id UUID NOT NULL REFERENCES lg_domain_ontology_term(id),
    to_term_id UUID NOT NULL REFERENCES lg_domain_ontology_term(id),
    relation_type VARCHAR(32) NOT NULL,
    confidence NUMERIC(5,4),
    source VARCHAR(32),
    reviewed BOOLEAN NOT NULL DEFAULT false,
    deleted SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_domain_ontology_relation IS '领域本体术语关系表';
COMMENT ON COLUMN lg_domain_ontology_relation.id IS '关系主键 UUID';
COMMENT ON COLUMN lg_domain_ontology_relation.project_id IS '所属项目 UUID';
COMMENT ON COLUMN lg_domain_ontology_relation.from_term_id IS '源术语 UUID';
COMMENT ON COLUMN lg_domain_ontology_relation.to_term_id IS '目标术语 UUID';
COMMENT ON COLUMN lg_domain_ontology_relation.relation_type IS '关系类型：SYNONYM/HYPERNYM/FORBIDDEN_MERGE';
COMMENT ON COLUMN lg_domain_ontology_relation.confidence IS '置信度 0~1，NUMERIC(5,4)';
COMMENT ON COLUMN lg_domain_ontology_relation.source IS '来源：CODE_DOC/PROJECT_DOC/DB/MANUAL/AI';
COMMENT ON COLUMN lg_domain_ontology_relation.reviewed IS '是否已人工审核';

CREATE INDEX IF NOT EXISTS idx_lg_domain_ontology_rel_project
    ON lg_domain_ontology_relation(project_id);
CREATE INDEX IF NOT EXISTS idx_lg_domain_ontology_rel_from
    ON lg_domain_ontology_relation(project_id, from_term_id);
CREATE INDEX IF NOT EXISTS idx_lg_domain_ontology_rel_to
    ON lg_domain_ontology_relation(project_id, to_term_id);
CREATE INDEX IF NOT EXISTS idx_lg_domain_ontology_rel_type
    ON lg_domain_ontology_relation(project_id, relation_type);
