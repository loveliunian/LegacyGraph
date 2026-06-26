-- ============================================
-- LegacyGraph 引入 LLM 功能的数据库增量更新
-- 依照详细设计文档添加所需表结构
-- ============================================

-- 连接数据库
\c legacy_graph;

-- ============================================
-- 1. Prompt 模板表 - 存储版本化的 Prompt 模板
-- ============================================
CREATE TABLE IF NOT EXISTS lg_prompt_template (
    id              BIGSERIAL PRIMARY KEY,
    template_code   VARCHAR(100) NOT NULL UNIQUE,
    version         VARCHAR(30) NOT NULL,
    scene           VARCHAR(50) NOT NULL,  -- code/doc/merge/test/review
    system_prompt   TEXT NOT NULL,
    domain_prompt   TEXT,
    task_prompt     TEXT NOT NULL,
    output_schema   JSONB NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_prompt_template IS 'Prompt 模板表';

-- ============================================
-- 2. LLM 提供者配置表 - 支持多模型、多提供者配置
-- ============================================
CREATE TABLE IF NOT EXISTS lg_llm_provider (
    id              BIGSERIAL PRIMARY KEY,
    provider_code   VARCHAR(50) NOT NULL UNIQUE,  -- openai/qwen/glm/local
    model_id        VARCHAR(100) NOT NULL,
    endpoint        TEXT,
    deployment_mode VARCHAR(20) NOT NULL,         -- cloud/private/hybrid
    api_config      JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lg_llm_provider IS 'LLM 提供者配置表';

-- ============================================
-- 3. Prompt 运行记录表 - 审计、缓存、重试
-- ============================================
CREATE TABLE IF NOT EXISTS lg_prompt_run (
    id              BIGSERIAL PRIMARY KEY,
    project_id      UUID NOT NULL REFERENCES lg_project(id),
    task_type       VARCHAR(50) NOT NULL,
    provider_code   VARCHAR(50) NOT NULL,
    model_id        VARCHAR(100) NOT NULL,
    template_code   VARCHAR(100) NOT NULL,
    template_version VARCHAR(30) NOT NULL,
    input_hash      CHAR(64) NOT NULL,      -- SHA-256 of input for caching
    masked_input    JSONB NOT NULL,         -- 脱敏后的输入
    raw_output      JSONB,                  -- 原始输出
    parsed_output   JSONB,                  -- 解析后的结构化输出
    prompt_tokens   INT,
    completion_tokens INT,
    latency_ms      INT,
    status          VARCHAR(20) NOT NULL,   -- success/failed/review
    created_by      VARCHAR(100),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_prompt_run_project ON lg_prompt_run(project_id);
CREATE INDEX idx_lg_prompt_run_input_hash ON lg_prompt_run(input_hash);
CREATE INDEX idx_lg_prompt_run_status ON lg_prompt_run(status);

COMMENT ON TABLE lg_prompt_run IS 'Prompt 运行记录表';

-- ============================================
-- 4. 向量文档表 - pgvector 存储代码、文档分片的向量
-- ============================================
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS lg_vector_document (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT NOT NULL,
    chunk_type      VARCHAR(50) NOT NULL,      -- code/doc/db/ui
    source_uri      TEXT NOT NULL,
    source_hash    CHAR(64) NOT NULL,
    chunk_index     INT NOT NULL,
    content         TEXT NOT NULL,
    content_sha256  CHAR(64) NOT NULL,
    meta            JSONB NOT NULL DEFAULT '{}'::JSONB,
    embedding       vector(768),
    embedding_model VARCHAR(100) NOT NULL,
    embedding_dim   INT NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lg_vector_document_project_type
    ON lg_vector_document(project_id, chunk_type);

CREATE INDEX idx_lg_vector_document_meta_gin
    ON lg_vector_document USING gin(meta);

-- 向量索引会在实际使用时根据 provider 和维度创建
-- CREATE INDEX ON lg_vector_document USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE lg_vector_document IS '向量文档表';

-- ============================================
-- 5. 增强现有表 - 添加 LLM 集成所需字段
-- ============================================

-- lg_fact 增强
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'lg_fact' AND column_name = 'evidence_ids') THEN
        ALTER TABLE lg_fact
        ADD COLUMN evidence_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
        ADD COLUMN extractor_name VARCHAR(100),
        ADD COLUMN extractor_version VARCHAR(30),
        ADD COLUMN prompt_run_id BIGINT,
        ADD COLUMN pii_masked BOOLEAN NOT NULL DEFAULT FALSE,
        ADD COLUMN review_status VARCHAR(20) DEFAULT 'pending',
        ADD COLUMN verified_by_test BOOLEAN NOT NULL DEFAULT FALSE;
    END IF;
END $$;

-- lg_graph_node 增强
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'lg_graph_node' AND column_name = 'alias_names') THEN
        ALTER TABLE lg_graph_node
        ADD COLUMN alias_names JSONB NOT NULL DEFAULT '[]'::JSONB,
        ADD COLUMN evidence_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
        ADD COLUMN semantic_vector_ref BIGINT,
        ADD COLUMN verified_score NUMERIC(5,4) DEFAULT 0;
    END IF;
END $$;

-- lg_graph_edge 增强
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'lg_graph_edge' AND column_name = 'evidence_ids') THEN
        ALTER TABLE lg_graph_edge
        ADD COLUMN evidence_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
        ADD COLUMN relation_status VARCHAR(20) DEFAULT 'candidate',
        ADD COLUMN verified_score NUMERIC(5,4) DEFAULT 0;
    END IF;
END $$;

-- ============================================
-- 初始化数据 - 添加默认 LLM 提供者配置
-- ============================================

-- OpenAI 默认配置
INSERT INTO lg_llm_provider (provider_code, model_id, endpoint, deployment_mode, api_config)
VALUES ('openai', 'gpt-5.5', 'https://api.openai.com/v1', 'cloud', '{"api_key": "${OPENAI_API_KEY}"}'::JSONB)
ON CONFLICT (provider_code) DO NOTHING;

-- OpenAI GPT-5.4 用于批量处理
INSERT INTO lg_llm_provider (provider_code, model_id, endpoint, deployment_mode, api_config)
VALUES ('openai-batch', 'gpt-5.4', 'https://api.openai.com/v1', 'cloud', '{"api_key": "${OPENAI_API_KEY}"}'::JSONB)
ON CONFLICT (provider_code) DO NOTHING;

-- OpenAI Embedding
INSERT INTO lg_llm_provider (provider_code, model_id, endpoint, deployment_mode, api_config)
VALUES ('openai-embedding', 'text-embedding-3-small', 'https://api.openai.com/v1', 'cloud', '{"api_key": "${OPENAI_API_KEY}"}'::JSONB)
ON CONFLICT (provider_code) DO NOTHING;

-- ============================================
-- 初始化数据 - 添加默认 Prompt 模板
-- ============================================

-- 代码事实理解模板
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'code-fact-extraction',
    '1.0',
    'code',
    '你是企业级遗留系统代码分析专家。
你只能根据输入的代码事实输出结论。
不允许编造未被代码证据支持的业务语义。
输出必须严格符合 JSON Schema。',
    NULL,
    '输入是一段从代码中静态抽取的方法、类或接口定义，请：
1. 推断其业务语义和功能描述
2. 识别参数和返回值的业务含义
3. 补全可能存在的动态 SQL 分支逻辑
4. 对每条结论附上证据来源',
    '{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "FactExtractionResult",
  "type": "object",
  "required": ["factType", "projectId", "items"],
  "properties": {
    "factType": {
      "type": "string",
      "enum": ["API_ENDPOINT", "SERVICE_METHOD", "SQL_STATEMENT", "TABLE_SCHEMA", "PAGE_ACTION", "BUSINESS_PROCESS"]
    },
    "projectId": { "type": "string" },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["key", "name", "evidence", "confidence"],
        "properties": {
          "key": { "type": "string" },
          "name": { "type": "string" },
          "attributes": { "type": "object", "additionalProperties": true },
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["sourceType", "sourceUri"],
              "properties": {
                "sourceType": { "type": "string" },
                "sourceUri": { "type": "string" },
                "lineStart": { "type": "integer" },
                "lineEnd": { "type": "integer" },
                "excerpt": { "type": "string" }
              }
            }
          },
          "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
        }
      }
    }
  }
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 文档理解模板
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'doc-understanding',
    '1.0',
    'doc',
    '你是企业级遗留系统业务分析师。
你只能根据输入证据输出结论。
不允许编造未被证据支持的流程。
输出必须严格符合 JSON Schema。',
    NULL,
    '输入包括：产品文档片段、前端页面动作、后端接口定义、相关 SQL、表结构。
请抽取业务流程、参与角色、业务对象、规则与状态流转。
对每条结论附 evidence 引用。',
    '{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "BusinessProcessExtractionResult",
  "type": "object",
  "required": ["processes"],
  "properties": {
    "processes": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["key", "name", "description"],
        "properties": {
          "key": { "type": "string" },
          "name": { "type": "string" },
          "description": { "type": "string" },
          "roles": { "type": "array", "items": { "type": "string" } },
          "objects": { "type": "array", "items": { "type": "string" } },
          "rules": { "type": "array", "items": { "type": "string" } },
          "states": { "type": "array", "items": { "type": "object" } },
          "evidence": {
            "type": "array",
            "items": {
              "type": "object",
              "required": ["sourceType", "sourceUri"],
              "properties": {
                "sourceType": { "type": "string" },
                "sourceUri": { "type": "string" },
                "lineStart": { "type": "integer" },
                "lineEnd": { "type": "integer" },
                "excerpt": { "type": "string" }
              }
            }
          },
          "confidence": { "type": "number", "minimum": 0, "maximum": 1 }
        }
      }
    }
  }
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 图谱合并决策模板
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'graph-merge-decision',
    '1.0',
    'merge',
    '你是图谱合并专家。你的任务是判断两个图谱节点是否应该合并为一个。
仔细分析名称、语义、结构、邻居和证据，做出合理决策。
输出必须严格符合 JSON Schema。',
    NULL,
    '给定两个候选图谱节点，请判断它们是否表示同一个概念，应该合并。
分析名称相似度、语义相似度、结构重叠度、邻居相似度和证据重叠度。
给出决策：AUTO_MERGE（自动合并）、REVIEW（需要人工审核）、REJECT（拒绝合并）',
    '{
  "type": "object",
  "required": ["candidateA", "candidateB", "decision", "score"],
  "properties": {
    "candidateA": { "type": "string" },
    "candidateB": { "type": "string" },
    "decision": {
      "type": "string",
      "enum": ["AUTO_MERGE", "REVIEW", "REJECT"]
    },
    "score": { "type": "number", "minimum": 0, "maximum": 1 },
    "reasons": {
      "type": "array",
      "items": { "type": "string" }
    },
    "positiveEvidenceIds": {
      "type": "array",
      "items": { "type": "string" }
    },
    "negativeEvidenceIds": {
      "type": "array",
      "items": { "type": "string" }
    }
  }
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 测试用例生成模板
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'test-case-generation',
    '1.0',
    'test',
    '你是测试架构师。
你必须同时生成操作步骤、接口断言、数据库断言与状态断言。
不确定的数据请显式标记 needHumanInput。
输出必须严格符合 JSON Schema。',
    NULL,
    '输入：
- 功能节点信息
- API 接口定义
- 请求参数与 DTO
- 相关写表信息
- 业务规则

请生成完整的测试用例，包括：
- 正常场景
- 权限场景
- 状态非法场景
- 数据不存在场景
- 边界场景',
    '{
  "type": "object",
  "required": ["featureKey", "caseName", "caseType", "steps", "assertions"],
  "properties": {
    "featureKey": { "type": "string" },
    "caseName": { "type": "string" },
    "caseType": {
      "type": "string",
      "enum": ["API", "E2E", "DB", "HYBRID"]
    },
    "preconditions": {
      "type": "array",
      "items": { "type": "string" }
    },
    "steps": {
      "type": "array",
      "items": { "type": "string" }
    },
    "request": { "type": "object", "additionalProperties": true },
    "assertions": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["type", "expression"],
        "properties": {
          "type": {
            "type": "string",
            "enum": ["HTTP", "JSON_PATH", "SQL", "STATE", "UI"]
          },
          "expression": { "type": "string" }
        }
      }
    },
    "needHumanInput": {
      "type": "array",
      "items": { "type": "string" }
    }
  }
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 功能映射模板 - 页面对齐接口
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'feature-mapping',
    '1.0',
    'code',
    '你是功能映射专家。你的任务是将页面、按钮、接口、权限和业务动作建立可追溯关系。
输出必须严格符合 JSON Schema。',
    NULL,
    '输入：
1. Vue 页面组件代码
2. axios/request 定义
3. Spring Controller 接口
4. 权限注解
5. 产品文档功能清单

请输出：
- 已确认映射关系
- 可能映射关系
- 未匹配项
- 每条关系的证据、置信度、冲突点',
    '{
  "type": "object",
  "required": ["mappings"],
  "properties": {
    "mappings": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["pageKey", "buttonName", "apiKey", "businessAction", "confidence"],
        "properties": {
          "pageKey": { "type": "string" },
          "buttonName": { "type": "string" },
          "apiKey": { "type": "string" },
          "businessAction": { "type": "string" },
          "permissionKey": { "type": "string" },
          "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
          "evidence": { "type": "array", "items": { "type": "object" } },
          "conflicts": { "type": "array", "items": { "type": "string" } }
        }
      }
    },
    "unmatched": {
      "type": "array",
      "items": { "type": "string" }
    }
  }
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
