-- ============================================
-- V93: CodeRepo 扩展 - PR 流水线支持（阶段三-3.2）
--
-- lg_code_repo 新增 provider / default_branch / review_team 字段
-- 支持真正的 Git 推送和 PR 创建
-- ============================================

ALTER TABLE lg_code_repo ADD COLUMN IF NOT EXISTS provider      VARCHAR(32);
ALTER TABLE lg_code_repo ADD COLUMN IF NOT EXISTS default_branch VARCHAR(128);
ALTER TABLE lg_code_repo ADD COLUMN IF NOT EXISTS review_team   VARCHAR(256);

COMMENT ON COLUMN lg_code_repo.provider       IS 'Git 提供商：github / gitlab / gitea';
COMMENT ON COLUMN lg_code_repo.default_branch IS '默认分支（main / master / develop）';
COMMENT ON COLUMN lg_code_repo.review_team    IS '默认 review 团队';
