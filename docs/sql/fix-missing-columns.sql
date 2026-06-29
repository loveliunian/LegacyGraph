-- ============================================
-- 修复 sys_user 表：添加缺失的 roles 和 permissions 字段
-- 说明：实体类定义中有这两个字段，但数据库表中漏掉了
-- ============================================

-- 添加 roles 字段（角色，逗号分隔）
ALTER TABLE sys_user ADD COLUMN roles VARCHAR(256);
COMMENT ON COLUMN sys_user.roles IS '角色（逗号分隔）';

-- 添加 permissions 字段（权限，逗号分隔）
ALTER TABLE sys_user ADD COLUMN permissions VARCHAR(512);
COMMENT ON COLUMN sys_user.permissions IS '权限（逗号分隔）';

-- 给默认 admin 用户添加 ADMIN 角色
UPDATE sys_user SET roles = 'ADMIN' WHERE username = 'admin';
