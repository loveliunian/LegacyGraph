-- ============================================
-- V17: 切换 Embedding 模型到硅基流动 SiliconFlow
-- ============================================
-- 硅基流动提供 BAAI/bge-large-zh-v1.5（1024维）中文 Embedding，
-- 性价比远高于 OpenAI text-embedding-3-small。
-- api_key 使用 ${SILICONFLOW_API_KEY} 占位符，
-- 由 LlmProviderService.resolveEnvPlaceholders() 在运行时解析。

INSERT INTO lg_llm_provider (provider_code, model_id, endpoint, deployment_mode, api_config, is_default, is_active)
VALUES ('openai-embedding', 'BAAI/bge-large-zh-v1.5', 'https://api.siliconflow.cn/v1', 'cloud',
        '{"api_key": "${SILICONFLOW_API_KEY}", "dimensions": 1024}'::JSONB,
        FALSE, TRUE)
ON CONFLICT (provider_code) DO UPDATE SET
    model_id   = EXCLUDED.model_id,
    endpoint   = EXCLUDED.endpoint,
    api_config = EXCLUDED.api_config,
    is_active  = TRUE;
