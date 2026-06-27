-- 修复登录问题：修改 id 列类型从 UUID 改为 VARCHAR
-- 问题原因：Java 代码使用 String 类型存储 UUID，但数据库定义为 UUID 类型，
-- PostgreSQL 不会自动将字符串转换为 UUID 类型，导致插入失败

ALTER TABLE lg_user ALTER COLUMN id TYPE VARCHAR(64);
