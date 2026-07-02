-- ============================================
-- V19: 切换 Embedding 到本地 Ollama (bge-m3)
-- ============================================
-- Ollama 是原生 macOS 本地 LLM 运行时，支持 GPU 加速（Metal）。
-- bge-m3 模型：BAAI 多语言，1024维，中文效果优秀。
-- 与当前 SiliconFlow bge-large (1024维) 维度相同，无需重建向量。
--
-- 前置条件：ollama serve 已运行 + ollama pull bge-m3 已完成
-- Ollama OpenAI 兼容端点：http://localhost:11434/v1

INSERT INTO lg_llm_provider (provider_code, model_id, endpoint, deployment_mode, api_config, is_default, is_active)
VALUES ('openai-embedding', 'bge-m3', 'http://localhost:11434/v1', 'local',
        '{"api_key": "ollama", "dimensions": 1024}'::JSONB,
        FALSE, TRUE)
ON CONFLICT (provider_code) DO UPDATE SET
    model_id   = EXCLUDED.model_id,
    endpoint   = EXCLUDED.endpoint,
    deployment_mode = EXCLUDED.deployment_mode,
    api_config = EXCLUDED.api_config,
    is_active  = TRUE;
