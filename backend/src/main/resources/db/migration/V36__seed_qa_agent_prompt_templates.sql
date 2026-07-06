-- ============================================
-- V36: 补充 QA Agent 缺失的 Prompt 模板
-- 包括：qa-answer-enhanced、intent-classifier、query-rewriter、hyde-generator
-- ============================================

-- 1. qa-answer-enhanced: 增强版问答模板
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'qa-answer-enhanced',
    '1.0',
    'qa',
    '你是一个代码知识图谱问答助手。根据提供的上下文信息回答用户问题。',
    NULL,
    '## 回答规则
1. 基于提供的上下文回答，不要编造信息
2. 如果上下文不足以回答问题，明确说明"根据现有信息无法回答"
3. 引用具体来源（文档、代码、图谱节点）
4. 使用简洁、专业的语言

## 对话历史
{history}

## 检索上下文
{context}

## 用户问题
{question}

## 回答',
    '{
  "answer": "回答内容",
  "confidence": 0.85,
  "sources": ["来源1", "来源2"]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 2. intent-classifier: 查询意图分类器
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'intent-classifier',
    '1.0',
    'qa',
    '你是一个查询意图分类器。根据用户问题和对话历史，判断查询类型。',
    NULL,
    '## 分类规则
- FACT_LOOKUP: 查找具体事实（方法列表、表结构、接口参数）
- STRUCTURAL: 查找系统结构（模块关系、依赖链路）
- RELATIONAL: 查找实体间关系（调用关系、数据流）
- COMPARATIVE: 对比两个或多个实体
- TEMPORAL: 涉及时间、版本变更
- EXPLANATION: 需要解释原因、设计决策

## 用户问题
{question}

## 对话历史
{history}

输出 JSON：{"intent": "FACT_LOOKUP", "confidence": 0.9}

注意：只输出 JSON，不要输出其他内容。',
    '{
  "intent": "FACT_LOOKUP",
  "confidence": 0.9
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 3. query-rewriter: 查询改写器
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'query-rewriter',
    '1.0',
    'qa',
    '你是一个查询改写专家。将用户问题改写为 2-3 个语义等价的变体，以提高检索召回率。',
    NULL,
    '## 改写策略
1. 同义词替换：用不同词汇表达相同概念
2. 视角转换：从不同角度描述同一问题
3. 具体化/抽象化：将抽象问题具体化，或将具体问题抽象化

## 查询意图
{intent}

## 原始问题
{question}

输出 JSON：{"rewrites": ["改写1", "改写2", "改写3"]}

注意：只输出 JSON，不要输出其他内容。',
    '{
  "rewrites": ["改写1", "改写2", "改写3"]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;

-- 4. hyde-generator: HyDE 假设文档生成器
INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'hyde-generator',
    '1.0',
    'qa',
    '请根据问题生成一个假设性的答案段落。这个答案不需要准确，只需要在语义和格式上与真实答案相关，用于提高检索效果。',
    NULL,
    '## 问题
{question}

## 要求
- 生成 100-200 字的假设性答案
- 使用专业的技术术语
- 包含可能的关键词和实体名称

输出 JSON：{"hypotheticalAnswer": "假设性答案内容"}

注意：只输出 JSON，不要输出其他内容。',
    '{
  "hypotheticalAnswer": "假设性答案内容"
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
