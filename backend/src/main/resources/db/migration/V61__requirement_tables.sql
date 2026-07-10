-- ============================================
-- V61: 需求结构化抽取与图谱构建（Task 6）
-- 注意：V60 版本号已被 graph_release（Task 7）占用，本迁移使用 V61
--
-- lg_requirement          需求主表（LLM 抽取的目标 + 原文）
-- lg_requirement_item     需求条目（R1/R2...，每条含验收条件与约束）
-- lg_acceptance_criterion 验收条件
-- 见 doc §6 需求结构化抽取与图谱构建
-- ============================================

-- 1. 需求主表
CREATE TABLE IF NOT EXISTS lg_requirement (
    id          UUID PRIMARY KEY,
    project_id  UUID NOT NULL,
    text        TEXT NOT NULL,
    goal        TEXT,
    status      VARCHAR(32) NOT NULL DEFAULT 'ANALYZED',
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lg_requirement_project ON lg_requirement(project_id);
COMMENT ON TABLE lg_requirement IS '需求主表 - 输入原文 + LLM 抽取的整体目标';

-- 2. 需求条目表
CREATE TABLE IF NOT EXISTS lg_requirement_item (
    id              UUID PRIMARY KEY,
    requirement_id  UUID NOT NULL REFERENCES lg_requirement(id) ON DELETE CASCADE,
    code            VARCHAR(32) NOT NULL,
    text            TEXT NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lg_requirement_item_req ON lg_requirement_item(requirement_id);
COMMENT ON TABLE lg_requirement_item IS '需求条目 - 单条需求（如 R1/R2），挂验收条件与约束';

-- 3. 验收条件表
CREATE TABLE IF NOT EXISTS lg_acceptance_criterion (
    id                  UUID PRIMARY KEY,
    requirement_item_id UUID NOT NULL REFERENCES lg_requirement_item(id) ON DELETE CASCADE,
    text                TEXT NOT NULL,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_lg_acceptance_criterion_item ON lg_acceptance_criterion(requirement_item_id);
COMMENT ON TABLE lg_acceptance_criterion IS '验收条件 - 每个需求条目至少一条';
