-- ============================================
-- V74: QA 审计日志表
--
-- 记录 QA 问答链路中 ACL 拦截/版本不匹配等审计事件，
-- 用于安全合规追溯与访问行为分析。
-- ============================================

CREATE TABLE IF NOT EXISTS lg_qa_audit_log (
    id               VARCHAR(40) PRIMARY KEY,
    project_id       VARCHAR(64),
    graph_release_id VARCHAR(40),
    principal        VARCHAR(64),
    question_hash    VARCHAR(64),
    acl_hash         VARCHAR(64),
    blocked_reason   VARCHAR(64),
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_qa_audit_project ON lg_qa_audit_log(project_id, created_at);
COMMENT ON TABLE lg_qa_audit_log IS 'QA 审计日志 - 记录 ACL 拦截与版本不匹配等安全审计事件';
