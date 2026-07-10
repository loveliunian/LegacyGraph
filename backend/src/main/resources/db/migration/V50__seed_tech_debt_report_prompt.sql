-- ============================================
-- V50: 技术债嗅探（TECH_DEBT）提示词模板种子数据
-- --------------------------------------------
-- 背景：QueryIntent 新增 TECH_DEBT 意图，用于识别"系统有哪些技术债/
--   代码质量如何/有没有循环依赖/死代码"类技术债查询。
--   配套该意图，新增"技术债分析报告"提示词模板，输出结构含：
--     1. 循环依赖（环路径 + 环类型）
--     2. 过大类/方法（规模指标 + 拆分建议）
--     3. 架构违规（Controller→Mapper 跳层）
--     4. 死代码（fanIn=0 节点）
--     5. 高耦合模块（fanOut>20 + 解耦建议）
--     6. 优先级建议
--   同步落地于 classpath:/prompts/tech-debt-report.txt（DB 缺失时回退路径）。
-- 字段沿用 V12__seed_prompt_templates.sql / V49 的格式。
-- ============================================

INSERT INTO lg_prompt_template (template_code, version, scene, system_prompt, domain_prompt, task_prompt, output_schema, is_active)
VALUES (
    'tech-debt-report',
    '1.0',
    'code',
    '你是一个技术债分析专家。请基于图谱中检测到的循环依赖、过大类、架构违规、死代码与高耦合模块数据，为用户生成一份结构化的技术债报告，并给出优先级排序的修复建议。',
    NULL,
    '## 用户问题
{question}

## 检索到的上下文（循环依赖 / 过大类 / 架构违规 / 死代码 / 高耦合模块）
{context}

## 输出要求
报告须包含以下章节，按顺序输出，缺失项填"（无）"并说明原因：

### 1. 循环依赖
- 列出检测到的环路径（Package/Class/Method 级）
- 标注环类型：DEPENDS_ON（包级循环）或 CALLS（方法/类级循环）

### 2. 过大类/方法
- 列出 lineCount > 1000 或 methodCount > 30 的类
- 附规模指标：行数、方法数、字段数
- 给出拆分建议

### 3. 架构违规
- 列出 Controller → Mapper 跳层调用
- 标注违规类型和位置

### 4. 死代码
- 列出 fanIn=0 的 Method/Class 节点
- 标注是否为入口节点

### 5. 高耦合模块
- 列出 fanOut > 20 的 Package 节点
- 给出解耦建议

### 6. 优先级建议
1. 立即修复：循环依赖（阻断编译/部署）
2. 近期重构：过大类（影响可维护性）
3. 长期清理：死代码

## 重要约束
- 只依据上下文中检索到的图谱节点与边推断，不得编造上下文中不存在的问题。
- 上下文不足以确定的部分，明确标注"（推断）"或"待确认"。',
    '{
  "circularDependencies": [
    { "path": ["pkg.A", "pkg.B", "pkg.A"], "level": "Class", "type": "CALLS" }
  ],
  "largeClasses": [
    { "className": "com.example.BigService", "lineCount": 1200, "methodCount": 35, "fieldCount": 20, "splitSuggestion": "建议拆分" }
  ],
  "architectureViolations": [
    { "controller": "com.example.UserController", "mapper": "com.example.UserMapper", "type": "LAYER_SKIP" }
  ],
  "deadCode": [
    { "nodeName": "unusedMethod", "nodeType": "Method", "isEntry": false }
  ],
  "highCouplingModules": [
    { "packageName": "com.example.service", "fanOut": 25, "decouplingSuggestion": "建议解耦" }
  ],
  "priorityAdvice": [
    { "priority": "立即修复", "description": "循环依赖（阻断编译/部署）" }
  ]
}'::JSONB,
    TRUE
) ON CONFLICT (template_code) DO NOTHING;
