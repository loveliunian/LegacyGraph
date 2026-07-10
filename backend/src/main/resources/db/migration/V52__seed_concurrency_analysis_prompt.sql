-- ============================================
-- V52: 事务并发（CONCURRENCY）提示词模板种子数据
-- --------------------------------------------
-- 背景：QueryIntent 新增 CONCURRENCY 意图，用于识别"事务边界是什么/
--   传播行为/并发风险/self-invocation"类事务并发查询。
--   配套该意图，新增"事务并发分析"提示词模板，输出结构含：
--     1. 事务边界（@Transactional 方法 + 传播行为 + 隔离级别）
--     2. 事务传播链路（沿 CALLS 边追踪）
--     3. 潜在风险（self-invocation / @Async 异常丢失 / synchronized 集群失效）
--     4. 并发安全（锁粒度和有效性）
--     5. 修复建议
--   同步落地于 classpath:/prompts/concurrency-analysis.txt（DB 缺失时回退路径）。
-- 字段沿用 V12__seed_prompt_templates.sql / V49 / V50 / V51 的格式。
-- ============================================

INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'concurrency-analysis',
    '1.0',
    'code',
    '你是一个事务与并发分析专家。请基于图谱中的 TransactionScope 节点、Method 节点的事务属性（transactional/propagation/isolation/async/lockType/txFailureRisk）与 BOUND_BY 边，为用户生成一份结构化的事务并发分析报告，并给出修复建议。',
    NULL,
    '## 用户问题
{question}

## 检索到的上下文（TransactionScope 节点 / 事务 Method 属性 / BOUND_BY 边 / 并发锁节点）
{context}

## 输出要求
报告须包含以下章节，按顺序输出，缺失项填"（无）"并说明原因：

### 1. 事务边界
- 列出 @Transactional 标注的方法（transactional=true 的 Method 节点）
- 附传播行为（propagation）和隔离级别（isolation）
- 列出同事务内的方法（通过 TransactionScope 节点 + BOUND_BY 边聚合）

### 2. 事务传播链路
- 从入口方法沿 CALLS 边追踪事务传播
- 标注每个方法的事务行为（加入外层事务/新开事务/无事务）

### 3. 潜在风险
- self-invocation 导致 @Transactional 失效（txFailureRisk=true 的方法）
- @Async 方法异常丢失风险
- synchronized 在集群环境无效

### 4. 并发安全
- 列出 synchronized/lock 节点（lockType 非空的方法）
- 分析锁粒度和有效性

### 5. 修复建议
1. self-invocation → 拆分到独立 Bean 或用 AopContext.currentProxy()
2. synchronized → 改 Redis 分布式锁

## 重要约束
- 只依据上下文中检索到的图谱节点与边推断，不得编造上下文中不存在的事务风险。
- 上下文不足以确定的部分，明确标注"（推断）"或"待确认"。',
    '{
  "transactionBoundaries": [
    { "method": "OrderService.createOrder", "propagation": "REQUIRED", "isolation": "DEFAULT", "className": "com.example.OrderService" }
  ],
  "propagationChains": [
    { "entryMethod": "OrderController.create", "chain": ["OrderController.create -> OrderService.createOrder -> OrderMapper.insert"] }
  ],
  "risks": [
    { "type": "SELF_INVOCATION", "method": "OrderService.updateStatus", "reason": "self-invocation 导致 @Transactional 失效" },
    { "type": "ASYNC_EXCEPTION_LOSS", "method": "OrderService.sendNotification", "reason": "@Async 方法异常丢失风险" },
    { "type": "CLUSTER_SYNCHRONIZED", "method": "OrderService.syncStock", "reason": "synchronized 在集群环境无效" }
  ],
  "concurrencySafety": [
    { "method": "OrderService.syncStock", "lockType": "SYNCHRONIZED", "granularity": "方法级", "effectiveness": "集群环境无效" }
  ],
  "fixSuggestions": [
    { "priority": "高", "description": "self-invocation → 拆分到独立 Bean 或用 AopContext.currentProxy()" },
    { "priority": "中", "description": "synchronized → 改 Redis 分布式锁" }
  ]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
