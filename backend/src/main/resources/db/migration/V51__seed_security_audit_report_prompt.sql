-- ============================================
-- V51: 安全审计（SECURITY_AUDIT）提示词模板种子数据
-- --------------------------------------------
-- 背景：QueryIntent 新增 SECURITY_AUDIT 意图，用于识别"系统有哪些安全风险/
--   有没有 SQL 注入/硬编码密钥/敏感数据泄露"类安全审计查询。
--   配套该意图，新增"安全审计报告"提示词模板，输出结构含：
--     1. SQL 注入风险（位置 + 严重等级）
--     2. 硬编码密钥（类型 + 位置）
--     3. 敏感数据处理（sensitive 字段 + 脱敏位置）
--     4. 权限校验缺失（覆盖率统计）
--     5. 反序列化风险
--     6. 修复建议（按优先级排序）
--   同步落地于 classpath:/prompts/security-audit-report.txt（DB 缺失时回退路径）。
-- 字段沿用 V12__seed_prompt_templates.sql / V49 / V50 的格式。
-- ============================================

INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'security-audit-report',
    '1.0',
    'code',
    '你是一个安全审计专家。请基于图谱中检测到的安全风险节点（SecurityRisk）、敏感字段（Column sensitive）、脱敏边（MASKED_AT）与权限校验缺失数据，为用户生成一份结构化的安全审计报告，并给出按优先级排序的修复建议。',
    NULL,
    '## 用户问题
{question}

## 检索到的上下文（SecurityRisk 节点 / 敏感 Column / 脱敏边 / 未保护 ApiEndpoint）
{context}

## 输出要求
报告须包含以下章节，按顺序输出，缺失项填"（无）"并说明原因：

### 1. SQL 注入风险
- 列出 riskType=SQL_INJECTION 的 SecurityRisk 节点
- 标注严重等级和位置（文件:行号）

### 2. 硬编码密钥
- 列出 riskType=HARDCODED_SECRET 的 SecurityRisk 节点
- 标注密钥类型和位置

### 3. 敏感数据处理
- 列出 sensitive=true 的 Column 节点
- 标注脱敏位置（Column --MASKED_AT--> Method）

### 4. 权限校验缺失
- 列出无 REQUIRES_PERMISSION 边的 ApiEndpoint 节点
- 统计覆盖率：有权限校验 / 全量接口

### 5. 反序列化风险
- 列出 riskType=UNSAFE_DESERIALIZATION 的 SecurityRisk 节点

### 6. 修复建议
1. 立即修复：硬编码密钥 → 改为环境变量
2. 高危：SQL 注入 → 改为 #{} 参数化
3. 中危：补齐权限校验

## 重要约束
- 只依据上下文中检索到的图谱节点与边推断，不得编造上下文中不存在的安全风险。
- 上下文不足以确定的部分，明确标注"（推断）"或"待确认"。',
    '{
  "sqlInjectionRisks": [
    { "riskType": "SQL_INJECTION", "severity": "HIGH", "file": "UserMapper.java", "line": 42, "detail": "SQL 注入风险描述" }
  ],
  "hardcodedSecrets": [
    { "riskType": "HARDCODED_SECRET", "severity": "CRITICAL", "file": "Config.java", "line": 10, "secretType": "password" }
  ],
  "sensitiveData": [
    { "column": "id_card", "table": "user", "maskedAt": "UserService.maskIdCard" }
  ],
  "permissionCoverage": {
    "protectedCount": 50,
    "totalCount": 80,
    "coverageRate": 0.625,
    "unprotectedEndpoints": ["/api/users/list", "/api/users/export"]
  },
  "deserializationRisks": [
    { "riskType": "UNSAFE_DESERIALIZATION", "severity": "HIGH", "file": "ImportService.java", "line": 25 }
  ],
  "fixSuggestions": [
    { "priority": "立即修复", "description": "硬编码密钥 → 改为环境变量" },
    { "priority": "高危", "description": "SQL 注入 → 改为 #{} 参数化" },
    { "priority": "中危", "description": "补齐权限校验" }
  ]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
