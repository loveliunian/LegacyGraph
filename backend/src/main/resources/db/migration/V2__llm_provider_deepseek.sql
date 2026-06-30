-- ============================================
-- V2: LLM Provider 增强 — 添加 is_default / is_active 列，默认切换为 DeepSeek
-- ============================================

-- 1. 为新字段添加列（使用 IF NOT EXISTS 避免重复执行报错）
ALTER TABLE lg_llm_provider ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE lg_llm_provider ADD COLUMN IF NOT EXISTS is_active  BOOLEAN NOT NULL DEFAULT TRUE;

-- 2. 将已有 OpenAI 配置标记为备用（非默认）
UPDATE lg_llm_provider SET is_default = FALSE WHERE provider_code LIKE 'openai%';

-- 3. 插入 DeepSeek 作为新的默认提供商（OpenAI 兼容 API）
INSERT INTO lg_llm_provider (provider_code, model_id, endpoint, deployment_mode, api_config, is_default, is_active)
VALUES ('deepseek', 'deepseek-chat', 'https://api.deepseek.com/v1', 'cloud',
        '{"api_key": "${DEEPSEEK_API_KEY:your-deepseek-api-key-here}", "temperature": 0.1, "max_tokens": 8192}'::JSONB,
        TRUE, TRUE)
ON CONFLICT (provider_code) DO UPDATE SET
    is_default = TRUE,
    is_active  = TRUE;

-- 4. DeepSeek Reasoner 模型（用于复杂推理）
INSERT INTO lg_llm_provider (provider_code, model_id, endpoint, deployment_mode, api_config, is_default, is_active)
VALUES ('deepseek-reasoner', 'deepseek-reasoner', 'https://api.deepseek.com/v1', 'cloud',
        '{"api_key": "${DEEPSEEK_API_KEY:your-deepseek-api-key-here}", "temperature": 0.0, "max_tokens": 32768}'::JSONB,
        FALSE, TRUE)
ON CONFLICT (provider_code) DO NOTHING;

-- 5. 确保 Embedding 仍用 OpenAI（DeepSeek 暂无 embedding 模型）
INSERT INTO lg_llm_provider (provider_code, model_id, endpoint, deployment_mode, api_config, is_default, is_active)
VALUES ('openai-embedding', 'text-embedding-3-small', 'https://api.openai.com/v1', 'cloud',
        '{"api_key": "${OPENAI_API_KEY:your-openai-api-key-here}", "dimensions": 768}'::JSONB,
        FALSE, TRUE)
ON CONFLICT (provider_code) DO NOTHING;
