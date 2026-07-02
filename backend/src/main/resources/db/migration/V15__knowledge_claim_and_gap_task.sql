-- V15: KnowledgeClaim 与 GapTask 统一断言层
-- 目标：让所有来源先进入 Claim 层，再由 KnowledgeCompiler 编译回现有图谱

CREATE TABLE IF NOT EXISTS lg_knowledge_claim (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES lg_project(id),
    version_id UUID NOT NULL REFERENCES lg_scan_version(id),
    subject_type VARCHAR(64) NOT NULL,
    subject_key VARCHAR(512) NOT NULL,
    predicate VARCHAR(64) NOT NULL,
    object_type VARCHAR(64),
    object_key VARCHAR(512),
    object_value TEXT,
    qualifiers JSONB NOT NULL DEFAULT '{}'::JSONB,
    evidence_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
    supporting_claim_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
    contradicting_claim_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
    source_type VARCHAR(64) NOT NULL,
    extractor VARCHAR(128),
    confidence NUMERIC(5,4) NOT NULL DEFAULT 0.5000,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRM',
    lineage JSONB NOT NULL DEFAULT '[]'::JSONB,
    compiled_node_id UUID,
    compiled_edge_id UUID,
    compile_status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    deleted SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, subject_type, subject_key, predicate, object_type, object_key)
);

CREATE INDEX IF NOT EXISTS idx_lg_knowledge_claim_project_version
    ON lg_knowledge_claim(project_id, version_id);
CREATE INDEX IF NOT EXISTS idx_lg_knowledge_claim_subject
    ON lg_knowledge_claim(project_id, version_id, subject_type, subject_key);
CREATE INDEX IF NOT EXISTS idx_lg_knowledge_claim_predicate
    ON lg_knowledge_claim(project_id, version_id, predicate);
CREATE INDEX IF NOT EXISTS idx_lg_knowledge_claim_status
    ON lg_knowledge_claim(project_id, version_id, status);
CREATE INDEX IF NOT EXISTS idx_lg_knowledge_claim_qualifiers_gin
    ON lg_knowledge_claim USING GIN(qualifiers);
CREATE INDEX IF NOT EXISTS idx_lg_knowledge_claim_evidence_gin
    ON lg_knowledge_claim USING GIN(evidence_ids);

COMMENT ON TABLE lg_knowledge_claim IS '证据化知识断言表';

CREATE TABLE IF NOT EXISTS lg_gap_task (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES lg_project(id),
    version_id UUID NOT NULL REFERENCES lg_scan_version(id),
    gap_type VARCHAR(64) NOT NULL,
    gap_key VARCHAR(512) NOT NULL,
    title VARCHAR(512) NOT NULL,
    description TEXT,
    severity VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    subject_type VARCHAR(64),
    subject_key VARCHAR(512),
    related_claim_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
    related_node_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
    evidence_ids JSONB NOT NULL DEFAULT '[]'::JSONB,
    suggested_action TEXT,
    agent_run_id BIGINT,
    priority_score NUMERIC(5,4) NOT NULL DEFAULT 0.5000,
    deleted SMALLINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(project_id, version_id, gap_type, gap_key)
);

CREATE INDEX IF NOT EXISTS idx_lg_gap_task_project_version
    ON lg_gap_task(project_id, version_id);
CREATE INDEX IF NOT EXISTS idx_lg_gap_task_type
    ON lg_gap_task(project_id, version_id, gap_type);
CREATE INDEX IF NOT EXISTS idx_lg_gap_task_status
    ON lg_gap_task(project_id, version_id, status);
CREATE INDEX IF NOT EXISTS idx_lg_gap_task_claims_gin
    ON lg_gap_task USING GIN(related_claim_ids);

COMMENT ON TABLE lg_gap_task IS '知识图谱缺口任务表';

-- ============================================
-- Prompt 模板 DB 种子（优先于 classpath 文件加载，见 PromptTemplateLoader）
-- 字段对齐 lg_prompt_template 实体：template_code / version / scene / system_prompt / domain_prompt / task_prompt / output_schema / is_active
-- ============================================

-- gap-finder：知识缺口分析
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'gap-finder',
    '1.0',
    'knowledge',
    '你是 LegacyGraph 的图谱缺口分析 Agent。你只能基于输入的 GapTask、Claim 摘要和证据摘要输出建议。',
    NULL,
    E'## 缺口任务\n{gapTask}\n\n## 相关 Claim\n{claims}\n\n## 相关证据\n{evidence}\n\n## 输出要求\n1. explanation 用中文说明为什么这是缺口。\n2. priorityScore 为 0~1，越高表示越应该优先补证。\n3. suggestedActions 必须是可执行动作，例如补扫代码、上传文档、生成测试、人工确认。\n4. requiredEvidenceTypes 只能从 code、db、doc、runtime、test、human_review 中选择。\n5. 不得编造输入中不存在的接口、表、方法或业务对象。\n\n## 输出格式\n{\n  "explanation": "缺口解释",\n  "priorityScore": 0.75,\n  "suggestedActions": ["补扫 Controller 与前端页面映射", "人工确认功能入口"],\n  "requiredEvidenceTypes": ["code", "doc"],\n  "needsHumanReview": true\n}',
    '{"type":"object","properties":{"explanation":{"type":"string"},"priorityScore":{"type":"number"},"suggestedActions":{"type":"array","items":{"type":"string"}},"requiredEvidenceTypes":{"type":"array","items":{"type":"string"}},"needsHumanReview":{"type":"boolean"}}}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- graph-rag-planner：GraphRAG 查询规划
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'graph-rag-planner',
    '1.0',
    'knowledge',
    '你是 LegacyGraph 的 GraphRAG 查询规划 Agent。你需要根据用户的自然语言问题，结合已有的知识断言（KnowledgeClaim），规划多步查询路径以回答该问题。',
    NULL,
    E'## 用户问题\n{question}\n\n## 相关 Claim（知识断言）\n{claims}\n\n## 规划要求\n\n1. **拆分子问题**：将用户问题拆分为 2-5 个可独立查询的子问题（subQuestions），按优先级排列。每个 subQuestion 包含：targetType（Feature/ApiEndpoint/Table/BusinessRule 等）、具体的 query 描述、priority（HIGH/MEDIUM/LOW）。\n2. **Claim 过滤**：设计 ClaimQuery 列表，每条指定 subjectType、predicate、sourceType、minConfidence 等过滤条件。\n3. **路径查询**：设计 PathQuery 列表，每条指定 startNodeType（起始节点类型）、relationshipPattern（边类型序列，如 CALLS|HANDLED_BY|READS）、endNodeType（目标节点类型）、pathDepth（最大跳数 1-5）。\n4. **证据类型**：列出回答问题所需的证据类型：CLAIM（知识断言）、CODE_AST（代码 AST）、TABLE_SCHEMA（表结构）、API_DOC（接口文档）、TRACE（运行时链路）、TEST_RESULT（测试结果）、MANUAL_REVIEW（人工审核）。\n\n## 规则\n1. 不得编造系统中不存在的接口、表、方法、业务对象。\n2. 如果输入中的 Claim 不足以回答用户问题，设为 needsHumanReview=true 并说明原因。\n3. 优先级排序：先查 Feature 入口（EXPOSED_BY）→ 再查 API 实现（HANDLED_BY/CALLS）→ 再查数据路径（READS/WRITES）→ 最后查规则/权限。\n4. 使用中文输出 reasoning 和所有描述性字段。\n\n## 输出格式\n{\n  "subQuestions": [{"targetType": "ApiEndpoint", "query": "...", "priority": "HIGH", "dependsOn": []}],\n  "claimQueries": [{"subjectType": "ApiEndpoint", "predicate": "HANDLED_BY", "sourceType": null, "minConfidence": 0.5}],\n  "pathQueries": [{"startNodeType": "Feature", "relationshipPattern": "EXPOSED_BY|HANDLED_BY|READS", "endNodeType": "Table", "pathDepth": 3}],\n  "requiredEvidenceTypes": ["CLAIM", "TABLE_SCHEMA"],\n  "reasoning": "先找到 Feature→API 入口，再沿调用链找到直接读写的数据表",\n  "needsHumanReview": false\n}',
    '{"type":"object","properties":{"subQuestions":{"type":"array","items":{"type":"object","properties":{"targetType":{"type":"string"},"query":{"type":"string"},"priority":{"type":"string"},"dependsOn":{"type":"array","items":{"type":"string"}}}}},"claimQueries":{"type":"array","items":{"type":"object","properties":{"subjectType":{"type":"string"},"predicate":{"type":"string"},"sourceType":{"type":"string"},"minConfidence":{"type":"number"}}}},"pathQueries":{"type":"array","items":{"type":"object","properties":{"startNodeType":{"type":"string"},"relationshipPattern":{"type":"string"},"endNodeType":{"type":"string"},"pathDepth":{"type":"integer"}}}},"requiredEvidenceTypes":{"type":"array","items":{"type":"string"}},"reasoning":{"type":"string"},"needsHumanReview":{"type":"boolean"}}}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
