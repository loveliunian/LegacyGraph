-- V33: 统一表名前缀 lg_
-- 将 sys_* 表和 migration_risk 表统一加上 lg_ 前缀

-- sys_* → lg_sys_*
ALTER TABLE IF EXISTS sys_config RENAME TO lg_sys_config;
ALTER TABLE IF EXISTS sys_dict RENAME TO lg_sys_dict;
ALTER TABLE IF EXISTS sys_dict_item RENAME TO lg_sys_dict_item;
ALTER TABLE IF EXISTS sys_user RENAME TO lg_sys_user;
ALTER TABLE IF EXISTS sys_user_role RENAME TO lg_sys_user_role;
ALTER TABLE IF EXISTS sys_role RENAME TO lg_sys_role;
ALTER TABLE IF EXISTS sys_operation_log RENAME TO lg_sys_operation_log;

-- migration_risk → lg_migration_risk
ALTER TABLE IF EXISTS migration_risk RENAME TO lg_migration_risk;

-- 更新索引名（如果有引用旧表名的索引）
-- PostgreSQL 在 RENAME TABLE 时会自动更新索引中的表引用，无需手动处理
