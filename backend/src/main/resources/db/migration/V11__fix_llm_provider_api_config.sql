-- ============================================
-- V11: 修复 LLM Provider api_config 可能为 NULL 的问题
-- ============================================
-- 问题：V2 迁移 ON CONFLICT DO UPDATE 未更新 api_config，
-- 如果 deepseek 行在迁移前已存在且 api_config 为 NULL，则永远不会被修复。
-- 本次迁移强制修复所有 provider_code 为 'deepseek' 或 'deepseek-reasoner'
-- 且 api_config 为 NULL 的行。

UPDATE lg_llm_provider
SET api_config = '{"api_key": "", "temperature": 0.1, "max_tokens": 8192}'::JSONB
WHERE provider_code = 'deepseek'
  AND (api_config IS NULL OR api_config::text = 'null');

UPDATE lg_llm_provider
SET api_config = '{"api_key": "", "temperature": 0.0, "max_tokens": 32768}'::JSONB
WHERE provider_code = 'deepseek-reasoner'
  AND (api_config IS NULL OR api_config::text = 'null');

-- 注意：api_key 设为空字符串，实际部署时需通过环境变量或手动 UPDATE 填入真实 key。
-- 应用启动后可在 LlmProvider 管理页面配置真实 API key。
