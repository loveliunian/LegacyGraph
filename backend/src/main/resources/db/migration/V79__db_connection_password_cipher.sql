-- L-01: DB 连接密码加密存储
-- 新增 password_cipher 列存储 AES-GCM 加密后的密码密文
-- 新增 password_kms_key_id 列预留 KMS 密钥 ID（生产环境使用）
-- 旧 password 列保留，仅作为前端回显（脱敏值），不再用于真实连接

ALTER TABLE lg_db_connection ADD COLUMN IF NOT EXISTS password_cipher VARCHAR(512);
ALTER TABLE lg_db_connection ADD COLUMN IF NOT EXISTS password_kms_key_id VARCHAR(64);

COMMENT ON COLUMN lg_db_connection.password_cipher IS 'AES-GCM 加密后的密码密文（Base64(IV+ciphertext)），连接时解密使用';
COMMENT ON COLUMN lg_db_connection.password_kms_key_id IS 'KMS 密钥 ID（生产环境），本地模式为 NULL';

-- 数据迁移：旧 password 列中如果存在未脱敏的明文密码（长度 > 5 且不含 *** 的），
-- 需要在应用启动时由 SecretCipher 一次性加密写入 password_cipher。
-- 此处不直接做 SQL 层迁移（因为加密逻辑在 Java 侧），仅标记列存在。
