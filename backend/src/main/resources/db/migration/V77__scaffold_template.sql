-- ============================================
-- V77: 脚手架模板表（G-21 项目脚手架模板生成）
--
-- 存储从图谱中识别的标准 CRUD 模板（Controller/Service/Mapper/Entity），
-- 供 SolutionPlanner 在生成 CREATE 步骤时复用项目既有代码骨架。
-- ============================================

CREATE TABLE IF NOT EXISTS lg_scaffold_template (
    id              VARCHAR(40) PRIMARY KEY,
    project_id      VARCHAR(64) NOT NULL,
    entity_name     VARCHAR(128) NOT NULL,
    layer           VARCHAR(32) NOT NULL,
    file_path       VARCHAR(512),
    code_skeleton   TEXT,
    annotations     TEXT,
    method_signatures TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_scaffold_project_entity ON lg_scaffold_template(project_id, entity_name);
COMMENT ON TABLE lg_scaffold_template IS '脚手架模板 - 标准 CRUD 代码骨架供方案生成复用';
