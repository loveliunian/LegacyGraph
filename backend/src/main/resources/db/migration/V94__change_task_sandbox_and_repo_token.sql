-- ============================================
-- V94: ChangeTask 增加 sandboxEnabled 开关 + CodeRepo 增加 accessToken
--      （漏点 ⑤ + 漏点 ⑧ 修复）
--
-- 阶段三-3.1 + 阶段三-3.2：
--   - sandboxEnabled：标记是否走 LocalSandboxExecutor 隔离验证
--   - accessToken：远程仓库访问令牌（建议落地前加密）
-- ============================================

ALTER TABLE lg_change_task ADD COLUMN IF NOT EXISTS sandbox_enabled SMALLINT NOT NULL DEFAULT 0;
COMMENT ON COLUMN lg_change_task.sandbox_enabled IS '是否启用沙箱隔离验证（阶段三-3.1 漏点 ⑤）';

ALTER TABLE lg_code_repo ADD COLUMN IF NOT EXISTS access_token TEXT;
COMMENT ON COLUMN lg_code_repo.access_token IS '远程仓库访问令牌（阶段三-3.2 漏点 ⑧，建议上线前加密）';