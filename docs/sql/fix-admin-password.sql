-- ============================================
-- 修复 admin 用户密码
-- 说明：init.sql 中的密码哈希有误，重新设置为正确的 BCrypt 哈希
-- 密码仍然是: admin123
-- ============================================

-- 更新 admin 用户密码为正确的 BCrypt 哈希
-- 明文密码: admin123
UPDATE sys_user
SET password = '$2a$10$nOUIsVL5SMg3x0azWRitOuBdYcmK/r0JZPwCrx0E0yBLV1GZkfY7xy'
WHERE username = 'admin';
