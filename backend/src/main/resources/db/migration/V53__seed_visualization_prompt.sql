-- ============================================
-- V53: 可视化（VISUALIZATION）提示词模板种子数据
-- --------------------------------------------
-- 背景：QueryIntent 新增 VISUALIZATION 意图，用于识别"画时序图/依赖图/
--   调用链图/数据流图"类可视化查询。
--   配套该意图，新增"可视化"提示词模板，输出结构含：
--     1. Mermaid 代码块（sequenceDiagram / graph LR / graph TD）
--     2. 文字说明
--     3. 缺失部分说明
--   同步落地于 classpath:/prompts/visualization.txt（DB 缺失时回退路径）。
-- 字段沿用 V12__seed_prompt_templates.sql / V49 / V50 / V51 / V52 的格式。
-- ============================================

INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'visualization',
    '1.0',
    'code',
    '你是一个可视化助手。请基于图谱反查结果（DiagramGenerator 生成的 Mermaid 图表代码或图谱子图数据），为用户生成 Mermaid 代码块和文字说明。支持时序图、依赖图、调用链图、数据流图、业务链路图五种图类型。',
    NULL,
    '## 用户问题
{question}

## 检索到的上下文（Mermaid 代码 / 图谱节点与边）
{context}

## 输出要求
1. 生成 ```mermaid 代码块
2. 附文字说明：图表展示了什么
3. 如图谱数据不足，说明缺失部分

## 支持的图类型

### 时序图（sequenceDiagram）
- 参与者：从调用链中提取的类/服务
- 消息：方法调用（含方法名和参数）

### 依赖图（graph LR）
- 节点：Package/Module
- 边：DEPENDS_ON

### 调用链图（graph TD）
- 正向：入口方法 → 下游调用
- 反向：目标方法 → 上游调用方

### 数据流图（graph LR）
- Table → SqlStatement → Service → ApiEndpoint

### 业务链路图（graph LR）
- BusinessDomain → BusinessProcess → ApiEndpoint

## 重要约束
- 只依据上下文中检索到的图谱节点与边生成图表，不得编造不存在的调用关系。
- 图表节点数量超过 30 时，截断并说明。
- Mermaid 语法须正确（特殊字符转义、别名唯一）。',
    '{
  "chartType": "sequenceDiagram",
  "mermaidCode": "sequenceDiagram\\n    participant C as OrderController\\n    C->>S: create()",
  "description": "该时序图展示了订单创建流程的方法调用链路",
  "missingParts": "OrderService.createOrder 下游调用 OrderMapper.insert 的数据未在图谱中"
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
